CREATE TABLE rain_event.projection_checkpoint (
    projection_name varchar(128) NOT NULL CHECK (projection_name ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    generation bigint NOT NULL CHECK (generation > 0),
    partition_depth integer NOT NULL CHECK (partition_depth BETWEEN 0 AND 62),
    partition_prefix bigint NOT NULL CHECK (
        partition_prefix >= 0
        AND partition_prefix <= CASE
            WHEN partition_depth = 0 THEN 0
            ELSE (1::bigint << partition_depth) - 1
        END
    ),
    log_id bytea NOT NULL CHECK (octet_length(log_id) = 16),
    contract_revision integer NOT NULL CHECK (contract_revision > 0),
    topology_fingerprint bytea NOT NULL CHECK (octet_length(topology_fingerprint) = 32),
    cursor_position bigint NOT NULL CHECK (cursor_position >= 0),
    cursor_bound_xid text CHECK (cursor_bound_xid IS NULL OR cursor_bound_xid ~ '^[0-9]+$'),
    cursor_reach bigint NOT NULL DEFAULT 0 CHECK (cursor_reach >= 0),
    fence bigint NOT NULL CHECK (fence > 0),
    lease_token uuid,
    lease_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (projection_name, generation, partition_depth, partition_prefix),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);

CREATE INDEX projection_checkpoint_lease_idx
    ON rain_event.projection_checkpoint (lease_until)
    WHERE lease_until IS NOT NULL;
