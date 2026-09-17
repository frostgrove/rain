CREATE TABLE rain_event.projection_topology (
    projection_name varchar(128) NOT NULL,
    generation bigint NOT NULL,
    log_id bytea NOT NULL CHECK (octet_length(log_id) = 16),
    contract_revision integer NOT NULL CHECK (contract_revision > 0),
    hasher_id varchar(128) NOT NULL CHECK (hasher_id ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    topology_fingerprint bytea NOT NULL CHECK (octet_length(topology_fingerprint) = 32),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (projection_name, generation),
    FOREIGN KEY (projection_name, generation)
        REFERENCES rain_event.projection_generation (projection_name, generation)
);

CREATE TABLE rain_event.projection_topology_member (
    projection_name varchar(128) NOT NULL,
    generation bigint NOT NULL,
    partition_depth integer NOT NULL CHECK (partition_depth BETWEEN 0 AND 62),
    partition_prefix bigint NOT NULL CHECK (
        partition_prefix >= 0
        AND partition_prefix <= CASE
            WHEN partition_depth = 0 THEN 0
            ELSE (1::bigint << partition_depth) - 1
        END
    ),
    state varchar(16) NOT NULL CHECK (state IN ('live', 'retired')),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    retired_at timestamptz,
    PRIMARY KEY (projection_name, generation, partition_depth, partition_prefix),
    FOREIGN KEY (projection_name, generation)
        REFERENCES rain_event.projection_topology (projection_name, generation)
        ON DELETE CASCADE,
    CHECK ((state = 'retired') = (retired_at IS NOT NULL))
);

CREATE INDEX projection_topology_member_live_idx
    ON rain_event.projection_topology_member (projection_name, generation, partition_depth, partition_prefix)
    WHERE state = 'live';

CREATE TABLE rain_event.projection_topology_retirement (
    projection_name varchar(128) NOT NULL,
    generation bigint NOT NULL,
    parent_depth integer NOT NULL CHECK (parent_depth BETWEEN 0 AND 61),
    parent_prefix bigint NOT NULL CHECK (
        parent_prefix >= 0
        AND parent_prefix <= CASE
            WHEN parent_depth = 0 THEN 0
            ELSE (1::bigint << parent_depth) - 1
        END
    ),
    first_child_depth integer NOT NULL CHECK (first_child_depth = parent_depth + 1),
    first_child_prefix bigint NOT NULL,
    second_child_depth integer NOT NULL CHECK (second_child_depth = parent_depth + 1),
    second_child_prefix bigint NOT NULL,
    cursor_position bigint NOT NULL CHECK (cursor_position >= 0),
    cursor_bound_xid text CHECK (cursor_bound_xid IS NULL OR cursor_bound_xid ~ '^[0-9]+$'),
    cursor_reach bigint NOT NULL CHECK (cursor_reach >= 0),
    parent_fence bigint NOT NULL CHECK (parent_fence > 0),
    retired_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (projection_name, generation, parent_depth, parent_prefix),
    FOREIGN KEY (projection_name, generation)
        REFERENCES rain_event.projection_topology (projection_name, generation),
    CHECK (
        first_child_prefix >= 0
        AND first_child_prefix <= (1::bigint << first_child_depth) - 1
        AND second_child_prefix >= 0
        AND second_child_prefix <= (1::bigint << second_child_depth) - 1
        AND first_child_prefix <> second_child_prefix
        AND first_child_prefix = parent_prefix
        AND second_child_prefix = parent_prefix + (1::bigint << parent_depth)
    )
);

/* The topology fingerprint is mutable only through the transaction-local split protocol. */
CREATE OR REPLACE FUNCTION rain_event.refuse_projection_generation_immutable_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.log_id IS DISTINCT FROM OLD.log_id
       OR NEW.contract_revision IS DISTINCT FROM OLD.contract_revision
       OR NEW.source_position IS DISTINCT FROM OLD.source_position
       OR NEW.barrier_position IS DISTINCT FROM OLD.barrier_position
       OR NEW.effect_policy IS DISTINCT FROM OLD.effect_policy
       OR NEW.effect_after_position IS DISTINCT FROM OLD.effect_after_position THEN
        RAISE EXCEPTION 'rain_event.projection_generation replay contract is immutable' USING ERRCODE = '55000';
    END IF;
    IF NEW.topology_fingerprint IS DISTINCT FROM OLD.topology_fingerprint
       AND current_setting('rain_event.projection_topology_split', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'rain_event.projection_generation topology changes only through projection split' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
