CREATE SCHEMA IF NOT EXISTS rain_event;

CREATE TABLE rain_event.event_schema_meta (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    schema_version integer NOT NULL CHECK (schema_version = 1),
    schema_fingerprint bytea NOT NULL CHECK (octet_length(schema_fingerprint) = 32),
    log_id bytea NOT NULL CHECK (octet_length(log_id) = 16),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

INSERT INTO rain_event.event_schema_meta (schema_version, schema_fingerprint, log_id)
VALUES (1, decode(repeat('00', 32), 'hex'), uuid_send(gen_random_uuid()));

CREATE TABLE rain_event.event_stream (
    namespace bytea NOT NULL CHECK (octet_length(namespace) = 32),
    family varchar(128) NOT NULL CHECK (family ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    stream_key text NOT NULL CHECK (octet_length(stream_key) BETWEEN 1 AND 512),
    version bigint NOT NULL CHECK (version >= 1),
    PRIMARY KEY (namespace, family, stream_key)
);

CREATE TABLE rain_event.event_record (
    position bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    writer_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    namespace bytea NOT NULL CHECK (octet_length(namespace) = 32),
    family varchar(128) NOT NULL CHECK (family ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    stream_key text NOT NULL CHECK (octet_length(stream_key) BETWEEN 1 AND 512),
    version bigint NOT NULL CHECK (version >= 1),
    type varchar(128) NOT NULL CHECK (type ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    revision integer NOT NULL CHECK (revision >= 1),
    payload bytea NOT NULL CHECK (octet_length(payload) <= 1048576),
    metadata bytea NOT NULL CHECK (octet_length(metadata) <= 4096),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT event_record_stream_fk FOREIGN KEY (namespace, family, stream_key)
        REFERENCES rain_event.event_stream (namespace, family, stream_key),
    CONSTRAINT event_record_stream_version_unique UNIQUE (namespace, family, stream_key, version)
);

CREATE INDEX event_record_stream_replay_idx
    ON rain_event.event_record (namespace, family, stream_key, version)
    INCLUDE (position, type, revision, payload, metadata, recorded_at);

CREATE INDEX event_record_global_log_idx
    ON rain_event.event_record (position)
    INCLUDE (writer_xid, namespace, family, stream_key, version, type, revision, payload, metadata, recorded_at);

CREATE TABLE rain_event.event_receipt (
    namespace bytea NOT NULL CHECK (octet_length(namespace) = 32),
    operation_key text NOT NULL CHECK (octet_length(operation_key) BETWEEN 1 AND 256),
    request_fingerprint bytea NOT NULL CHECK (octet_length(request_fingerprint) = 32),
    append_fingerprint bytea CHECK (append_fingerprint IS NULL OR octet_length(append_fingerprint) = 32),
    family varchar(128),
    stream_key text,
    first_position bigint,
    last_position bigint,
    first_version bigint,
    last_version bigint,
    event_count integer NOT NULL DEFAULT 0 CHECK (event_count >= 0 AND event_count <= 1000),
    response_type varchar(128),
    response_revision integer,
    response_payload bytea CHECK (response_payload IS NULL OR octet_length(response_payload) <= 65536),
    complete boolean NOT NULL DEFAULT false,
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (namespace, operation_key),
    CHECK (
        (event_count = 0 AND first_position IS NULL AND last_position IS NULL AND first_version IS NULL AND last_version IS NULL) OR
        (event_count > 0 AND first_position IS NOT NULL AND last_position IS NOT NULL AND first_version IS NOT NULL AND last_version IS NOT NULL)
    )
);

CREATE OR REPLACE FUNCTION rain_event.refuse_event_record_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'rain_event.event_record is append only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER event_record_immutable
    BEFORE UPDATE OR DELETE ON rain_event.event_record
    FOR EACH ROW EXECUTE FUNCTION rain_event.refuse_event_record_mutation();
