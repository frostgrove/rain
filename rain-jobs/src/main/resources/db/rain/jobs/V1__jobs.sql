-- rain-jobs. Three tables and one owner each: db-scheduler's poller, heartbeat writer and dead-execution
-- detector own scheduled_tasks; rain owns job_invocation (the attempt ledger, the lease and the fence's row)
-- and job_intent (deduplication reservations). No rain statement names scheduled_tasks except through
-- db-scheduler's own client.

-- db-scheduler 16.12.0 on PostgreSQL. The jar ships no DDL; these are exactly the columns its
-- JdbcTaskRepository and PostgreSqlJdbcCustomization statements read and write (read from the bytecode),
-- including the optional priority column enablePriority() orders by: ORDER BY priority DESC, execution_time ASC.
CREATE TABLE scheduled_tasks (
  task_name            TEXT        NOT NULL,
  task_instance        TEXT        NOT NULL,
  task_data            BYTEA,
  execution_time       TIMESTAMPTZ NOT NULL,
  picked               BOOLEAN     NOT NULL,
  picked_by            TEXT,
  last_success         TIMESTAMPTZ,
  last_failure         TIMESTAMPTZ,
  consecutive_failures INT,
  last_heartbeat       TIMESTAMPTZ,
  version              BIGINT      NOT NULL,
  priority             SMALLINT,
  PRIMARY KEY (task_name, task_instance)
);

-- The due poll (priority order), the dead-execution scan, and the unordered due scan.
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);

-- One order and its life. Every timestamp is written from the application's clock, never from now().
CREATE TABLE job_invocation (
  id               UUID        PRIMARY KEY,
  definition       TEXT        NOT NULL,
  profile          TEXT        NOT NULL,
  state            TEXT        NOT NULL CHECK (state IN ('queued', 'running', 'succeeded', 'failed', 'dead', 'cancelled')),
  priority         SMALLINT    NOT NULL,
  payload          JSONB       NOT NULL,
  dedupe_mode      TEXT        NOT NULL CHECK (dedupe_mode IN ('none', 'unique', 'collapse')),
  dedupe_key       TEXT,
  subject_key      TEXT,
  -- Which db-scheduler execution may deliver this order: the instance id carries it, a redrive raises it, so
  -- a stale execution of an earlier generation is refused instead of racing the new one.
  generation       INT         NOT NULL CHECK (generation >= 0),
  attempts         INT         NOT NULL CHECK (attempts >= 0),
  retry_spent      INT         NOT NULL CHECK (retry_spent >= 0),
  retry_limit      INT         NOT NULL CHECK (retry_limit >= 0),
  deferrals        INT         NOT NULL CHECK (deferrals >= 0),
  lease_token      UUID,
  lease_expires_at TIMESTAMPTZ,
  picked_by        TEXT,
  created_at       TIMESTAMPTZ NOT NULL,
  eligible_at      TIMESTAMPTZ NOT NULL,
  started_at       TIMESTAMPTZ,
  finished_at      TIMESTAMPTZ,
  failure_code     TEXT,
  failure_message  TEXT,
  -- Orders a unique or collapse reservation absorbed into this one; an absorbed order writes no row of its own.
  absorbed_count   INT         NOT NULL CHECK (absorbed_count >= 0),
  last_absorbed_at TIMESTAMPTZ,
  version          BIGINT      NOT NULL,
  CONSTRAINT job_invocation_dedupe_complete CHECK ((dedupe_mode = 'none') = (dedupe_key IS NULL)),
  CONSTRAINT job_invocation_lease_complete CHECK ((lease_token IS NULL) = (lease_expires_at IS NULL)),
  CONSTRAINT job_invocation_running_is_leased CHECK ((state = 'running') = (lease_token IS NOT NULL)),
  CONSTRAINT job_invocation_terminal_is_finished
    CHECK ((state IN ('succeeded', 'failed', 'dead', 'cancelled')) = (finished_at IS NOT NULL))
);

-- Retention: one profile's terminal rows, oldest first, deleted in bounded batches.
CREATE INDEX ix_job_invocation_retention ON job_invocation (profile, finished_at)
  WHERE state IN ('succeeded', 'failed', 'dead', 'cancelled');

-- Dead letters: a keyset page newest first, over every definition or over one.
CREATE INDEX ix_job_invocation_dead_letters ON job_invocation (finished_at DESC, id DESC)
  WHERE state IN ('failed', 'dead');
CREATE INDEX ix_job_invocation_dead_letters_definition ON job_invocation (definition, finished_at DESC, id DESC)
  WHERE state IN ('failed', 'dead');

-- Cancellation: the live work about one subject. The state is a key column as well as the predicate: a
-- locking read keeps the predicate's conditions to recheck them, and only a key column holds them as index
-- conditions rather than a filter, which the plan proof (criterion v1) requires.
CREATE INDEX ix_job_invocation_subject ON job_invocation (subject_key, state)
  WHERE state IN ('queued', 'running') AND subject_key IS NOT NULL;

-- The reaper: running rows whose lease lapsed, earliest lapse first; the state leads for the same reason.
CREATE INDEX ix_job_invocation_lease ON job_invocation (state, lease_expires_at)
  WHERE state = 'running';

-- Deduplication reservations. No foreign key to job_invocation: a reservation's history outlives the order it
-- reserved for, and retention removes each table on its own schedule.
CREATE TABLE job_intent (
  id            UUID        PRIMARY KEY,
  definition    TEXT        NOT NULL,
  profile       TEXT        NOT NULL,
  dedupe_key    TEXT        NOT NULL,
  mode          TEXT        NOT NULL CHECK (mode IN ('unique', 'collapse')),
  invocation_id UUID        NOT NULL,
  reserved_at   TIMESTAMPTZ NOT NULL,
  released_at   TIMESTAMPTZ
);

-- The reservation itself: one holder per (definition, key) while held, any number of released rows behind it.
CREATE UNIQUE INDEX uq_job_intent_held ON job_intent (definition, dedupe_key) WHERE released_at IS NULL;

-- Releasing the reservation an invocation holds, in the statement that ends, claims or cancels it. Unique: an
-- invocation holds at most one reservation (one dedupe key per order, and a redrive reserves again only after the
-- terminal write released the last one), which also lets the planner treat the release as a keyed lookup.
CREATE UNIQUE INDEX uq_job_intent_held_invocation ON job_intent (invocation_id) WHERE released_at IS NULL;

-- Retention of released reservations, per profile.
CREATE INDEX ix_job_intent_retention ON job_intent (profile, released_at) WHERE released_at IS NOT NULL;
