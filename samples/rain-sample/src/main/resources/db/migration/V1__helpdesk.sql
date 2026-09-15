-- The helpdesk's own schema, in `public`. rain's module schemas (rain_access, rain_audit, rain_jobs, rain_llm) are
-- migrated beside it by their own Flyway histories; nothing here references them.

CREATE TABLE agents (
  id                UUID PRIMARY KEY,
  identifier        TEXT NOT NULL CHECK (identifier <> ''),
  active            BOOLEAN NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL,
  last_signed_in_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_agents_identifier ON agents (identifier);

-- Written and read through Spring Data JDBC (AgentProfileRepository).
CREATE TABLE agent_profiles (
  agent_id     UUID PRIMARY KEY REFERENCES agents (id),
  display_name TEXT NOT NULL CHECK (display_name <> ''),
  version      BIGINT NOT NULL CHECK (version >= 0)
);

CREATE TABLE tickets (
  id           UUID PRIMARY KEY,
  title        TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  body         TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 20000),
  status       TEXT NOT NULL CHECK (status IN ('open', 'closed')),
  priority     INTEGER NOT NULL CHECK (priority BETWEEN 0 AND 100),
  assignee     UUID REFERENCES agents (id),
  summary      TEXT,
  escalated_at TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL,
  version      BIGINT NOT NULL CHECK (version >= 1)
);

-- One index per declared query shape and scope (TicketPlanProofIT proves each statement bounded by one). No two indexes
-- serve one sort after nested equality columns, so the planner never chooses between a bounded scan and a filtered one.
-- every ticket, sort=-updatedAt
CREATE INDEX ix_tickets_updated_at ON tickets (updated_at, id);
-- every ticket, filter[status][eq], sort=-createdAt; also the open-ticket report
CREATE INDEX ix_tickets_status_created_at ON tickets (status, created_at, id);
-- every ticket, filter[assignee][eq] and filter[status][eq], sort=-priority; assigned, filter[status][eq], sort=-priority
CREATE INDEX ix_tickets_assignee_status_priority ON tickets (assignee, status, priority, id);
-- assigned, sort=-updatedAt
CREATE INDEX ix_tickets_assignee_updated_at ON tickets (assignee, updated_at, id);
-- the escalation sweep: open tickets not escalated yet, oldest first; its batch is read without a row lock, so the
-- predicate stays with the index and is no Filter
CREATE INDEX ix_tickets_escalation ON tickets (created_at, id) WHERE status = 'open' AND escalated_at IS NULL;
