CREATE TABLE rain_event.projection_hold (
    projection_name varchar(128) NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    partition_depth integer NOT NULL CHECK (partition_depth BETWEEN 0 AND 62),
    partition_prefix bigint NOT NULL CHECK (
        partition_prefix >= 0
        AND partition_prefix <= CASE
            WHEN partition_depth = 0 THEN 0
            ELSE (1::bigint << partition_depth) - 1
        END
    ),
    sequence_digest bytea NOT NULL CHECK (octet_length(sequence_digest) = 32),
    first_position bigint NOT NULL CHECK (first_position > 0),
    failure_code varchar(128) NOT NULL CHECK (failure_code ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    state varchar(16) NOT NULL CHECK (state IN ('held', 'redriving')),
    letter_count integer NOT NULL DEFAULT 0 CHECK (letter_count >= 0 AND letter_count <= 100000),
    letter_bytes integer NOT NULL DEFAULT 0 CHECK (letter_bytes >= 0 AND letter_bytes <= 67108864),
    fence bigint NOT NULL DEFAULT 0 CHECK (fence >= 0),
    lease_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (projection_name, generation, partition_depth, partition_prefix, sequence_digest),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK ((state = 'redriving') = (lease_token IS NOT NULL)),
    FOREIGN KEY (projection_name, generation, partition_depth, partition_prefix)
        REFERENCES rain_event.projection_checkpoint (projection_name, generation, partition_depth, partition_prefix)
);

CREATE TABLE rain_event.projection_letter (
    projection_name varchar(128) NOT NULL,
    generation bigint NOT NULL,
    partition_depth integer NOT NULL,
    partition_prefix bigint NOT NULL,
    sequence_digest bytea NOT NULL CHECK (octet_length(sequence_digest) = 32),
    position bigint NOT NULL CHECK (position > 0),
    namespace bytea NOT NULL CHECK (octet_length(namespace) = 32),
    family varchar(128) NOT NULL CHECK (family ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    stream_key text NOT NULL CHECK (octet_length(stream_key) BETWEEN 1 AND 512),
    stream_version bigint NOT NULL CHECK (stream_version > 0),
    type varchar(128) NOT NULL CHECK (type ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    revision integer NOT NULL CHECK (revision > 0),
    payload bytea NOT NULL CHECK (octet_length(payload) <= 1048576),
    metadata bytea NOT NULL CHECK (octet_length(metadata) <= 4096),
    recorded_at timestamptz NOT NULL,
    checksum bytea NOT NULL CHECK (octet_length(checksum) = 32),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_failure_code varchar(128) CHECK (last_failure_code IS NULL OR last_failure_code ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    last_failed_at timestamptz,
    PRIMARY KEY (projection_name, generation, partition_depth, partition_prefix, sequence_digest, position),
    FOREIGN KEY (projection_name, generation, partition_depth, partition_prefix, sequence_digest)
        REFERENCES rain_event.projection_hold (projection_name, generation, partition_depth, partition_prefix, sequence_digest)
        ON DELETE CASCADE,
    CHECK ((last_failure_code IS NULL) = (last_failed_at IS NULL))
);

CREATE INDEX projection_hold_lease_idx ON rain_event.projection_hold (lease_until) WHERE lease_until IS NOT NULL;

CREATE TABLE rain_event.projection_halt (
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
    position bigint NOT NULL CHECK (position > 0),
    failure_code varchar(128) NOT NULL CHECK (failure_code ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (projection_name, generation, partition_depth, partition_prefix),
    FOREIGN KEY (projection_name, generation, partition_depth, partition_prefix)
        REFERENCES rain_event.projection_checkpoint (projection_name, generation, partition_depth, partition_prefix)
);

CREATE TABLE rain_event.projection_hole (
    hole_id bigserial PRIMARY KEY,
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
    sequence_digest bytea NOT NULL CHECK (octet_length(sequence_digest) = 32),
    first_position bigint NOT NULL CHECK (first_position > 0),
    last_position bigint NOT NULL CHECK (last_position >= first_position),
    operator_type varchar(64) NOT NULL CHECK (operator_type ~ '^[a-z][a-z0-9_-]{0,63}$'),
    operator_id varchar(128) NOT NULL CHECK (octet_length(operator_id) BETWEEN 1 AND 512),
    reason text NOT NULL CHECK (octet_length(reason) BETWEEN 1 AND 1024),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    acknowledged_at timestamptz,
    acknowledged_operator_type varchar(64) CHECK (acknowledged_operator_type IS NULL OR acknowledged_operator_type ~ '^[a-z][a-z0-9_-]{0,63}$'),
    acknowledged_operator_id varchar(128) CHECK (acknowledged_operator_id IS NULL OR octet_length(acknowledged_operator_id) BETWEEN 1 AND 512),
    acknowledged_reason text CHECK (acknowledged_reason IS NULL OR octet_length(acknowledged_reason) BETWEEN 1 AND 1024),
    CHECK (
        (acknowledged_at IS NULL AND acknowledged_operator_type IS NULL AND acknowledged_operator_id IS NULL AND acknowledged_reason IS NULL)
        OR (acknowledged_at IS NOT NULL AND acknowledged_operator_type IS NOT NULL AND acknowledged_operator_id IS NOT NULL AND acknowledged_reason IS NOT NULL)
    )
);

CREATE INDEX projection_hole_unacknowledged_idx
    ON rain_event.projection_hole (projection_name, generation)
    WHERE acknowledged_at IS NULL;
