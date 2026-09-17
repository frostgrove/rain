CREATE TABLE rain_event.projection_catalog (
    projection_name varchar(128) PRIMARY KEY CHECK (projection_name ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    log_id bytea NOT NULL CHECK (octet_length(log_id) = 16),
    active_generation bigint CHECK (active_generation > 0),
    row_version bigint NOT NULL DEFAULT 1 CHECK (row_version > 0),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

CREATE TABLE rain_event.projection_generation (
    projection_name varchar(128) NOT NULL REFERENCES rain_event.projection_catalog (projection_name),
    generation bigint NOT NULL CHECK (generation > 0),
    log_id bytea NOT NULL CHECK (octet_length(log_id) = 16),
    contract_revision integer NOT NULL CHECK (contract_revision > 0),
    topology_fingerprint bytea NOT NULL CHECK (octet_length(topology_fingerprint) = 32),
    source_position bigint NOT NULL CHECK (source_position >= 0),
    barrier_position bigint NOT NULL CHECK (barrier_position >= source_position),
    effect_policy varchar(32) NOT NULL CHECK (effect_policy IN ('disabled', 'staged_durable')),
    effect_after_position bigint,
    state varchar(16) NOT NULL CHECK (state IN ('building', 'ready', 'active', 'retired', 'failed')),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    cutover_at timestamptz,
    retired_at timestamptz,
    PRIMARY KEY (projection_name, generation),
    CHECK (
        (effect_policy = 'disabled' AND effect_after_position IS NULL)
        OR (effect_policy = 'staged_durable' AND effect_after_position = barrier_position)
    ),
    CHECK ((state <> 'active') OR cutover_at IS NOT NULL),
    CHECK ((state = 'retired') = (retired_at IS NOT NULL))
);

CREATE INDEX projection_generation_state_idx
    ON rain_event.projection_generation (projection_name, state, generation);

CREATE OR REPLACE FUNCTION rain_event.refuse_projection_generation_immutable_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.log_id IS DISTINCT FROM OLD.log_id
       OR NEW.contract_revision IS DISTINCT FROM OLD.contract_revision
       OR NEW.topology_fingerprint IS DISTINCT FROM OLD.topology_fingerprint
       OR NEW.source_position IS DISTINCT FROM OLD.source_position
       OR NEW.barrier_position IS DISTINCT FROM OLD.barrier_position
       OR NEW.effect_policy IS DISTINCT FROM OLD.effect_policy
       OR NEW.effect_after_position IS DISTINCT FROM OLD.effect_after_position THEN
        RAISE EXCEPTION 'rain_event.projection_generation replay contract is immutable' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER projection_generation_immutable
    BEFORE UPDATE ON rain_event.projection_generation
    FOR EACH ROW EXECUTE FUNCTION rain_event.refuse_projection_generation_immutable_change();
