CREATE SCHEMA IF NOT EXISTS rain_i18n;

CREATE TABLE rain_i18n.i18n_schema_meta (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    schema_version integer NOT NULL CHECK (schema_version = 1),
    schema_fingerprint bytea NOT NULL CHECK (octet_length(schema_fingerprint) = 32),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

INSERT INTO rain_i18n.i18n_schema_meta (schema_version, schema_fingerprint)
VALUES (1, decode(repeat('00', 32), 'hex'));

CREATE TABLE rain_i18n.i18n_artifact (
    artifact_digest char(64) PRIMARY KEY CHECK (artifact_digest ~ '^[0-9a-f]{64}$'),
    artifact bytea NOT NULL CHECK (octet_length(artifact) BETWEEN 1 AND 16777216),
    revision varchar(128) NOT NULL CHECK (octet_length(revision) BETWEEN 1 AND 128),
    profile varchar(128) NOT NULL CHECK (octet_length(profile) BETWEEN 1 AND 128),
    engine varchar(128) NOT NULL CHECK (octet_length(engine) BETWEEN 1 AND 128),
    icu_cldr_tzdb_identity varchar(128) NOT NULL CHECK (octet_length(icu_cldr_tzdb_identity) BETWEEN 1 AND 128),
    trust_policy varchar(16) NOT NULL CHECK (trust_policy IN ('LOCAL_BUILD', 'SIGNED_REMOTE')),
    envelope_origin varchar(512),
    envelope_key_id varchar(128),
    envelope_algorithm varchar(32),
    envelope_signature bytea,
    verified_key_id varchar(128),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CHECK (
        (trust_policy = 'LOCAL_BUILD' AND envelope_origin IS NULL AND envelope_key_id IS NULL
            AND envelope_algorithm IS NULL AND envelope_signature IS NULL AND verified_key_id IS NULL)
        OR
        (trust_policy = 'SIGNED_REMOTE' AND envelope_origin IS NOT NULL AND envelope_key_id IS NOT NULL
            AND envelope_algorithm IS NOT NULL AND envelope_signature IS NOT NULL
            AND octet_length(envelope_signature) BETWEEN 1 AND 16384 AND verified_key_id IS NOT NULL)
    )
);

CREATE TABLE rain_i18n.i18n_release (
    scope varchar(128) NOT NULL CHECK (scope ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    revision varchar(128) NOT NULL CHECK (octet_length(revision) BETWEEN 1 AND 128),
    snapshot_digest char(64) NOT NULL CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'),
    artifact_digest char(64) NOT NULL REFERENCES rain_i18n.i18n_artifact (artifact_digest),
    actor varchar(256) NOT NULL CHECK (octet_length(actor) BETWEEN 1 AND 256),
    published_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (scope, revision, snapshot_digest)
);

CREATE TABLE rain_i18n.i18n_head (
    scope varchar(128) PRIMARY KEY CHECK (scope ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    revision varchar(128) NOT NULL CHECK (octet_length(revision) BETWEEN 1 AND 128),
    snapshot_digest char(64) NOT NULL CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'),
    head_version bigint NOT NULL CHECK (head_version >= 1),
    changed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT i18n_head_release_fk FOREIGN KEY (scope, revision, snapshot_digest)
        REFERENCES rain_i18n.i18n_release (scope, revision, snapshot_digest)
);

CREATE TABLE rain_i18n.i18n_retained_release (
    scope varchar(128) NOT NULL CHECK (scope ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    revision varchar(128) NOT NULL CHECK (octet_length(revision) BETWEEN 1 AND 128),
    snapshot_digest char(64) NOT NULL CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'),
    retained_version bigint NOT NULL CHECK (retained_version >= 1),
    reason varchar(32) NOT NULL CHECK (reason IN ('HEAD_REPLACED')),
    retained_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (scope, revision, snapshot_digest),
    CONSTRAINT i18n_retained_release_fk FOREIGN KEY (scope, revision, snapshot_digest)
        REFERENCES rain_i18n.i18n_release (scope, revision, snapshot_digest)
);

CREATE INDEX i18n_retained_release_evict_idx
    ON rain_i18n.i18n_retained_release (scope, retained_version);

CREATE TABLE rain_i18n.i18n_pin (
    pin_id uuid PRIMARY KEY,
    scope varchar(128) NOT NULL CHECK (scope ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    revision varchar(128) NOT NULL CHECK (octet_length(revision) BETWEEN 1 AND 128),
    snapshot_digest char(64) NOT NULL CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'),
    owner varchar(256) NOT NULL CHECK (octet_length(owner) BETWEEN 1 AND 256),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT i18n_pin_release_fk FOREIGN KEY (scope, revision, snapshot_digest)
        REFERENCES rain_i18n.i18n_release (scope, revision, snapshot_digest)
);

CREATE INDEX i18n_pin_live_release_idx
    ON rain_i18n.i18n_pin (scope, revision, snapshot_digest, expires_at);

CREATE TABLE rain_i18n.i18n_audit (
    audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope varchar(128) NOT NULL CHECK (scope ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    operation varchar(128) NOT NULL CHECK (operation ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    kind varchar(32) NOT NULL CHECK (kind IN ('PUBLISHED', 'ACTIVATED', 'ROLLED_BACK', 'PRUNED', 'PINNED', 'PIN_RELEASED')),
    actor varchar(256) NOT NULL CHECK (octet_length(actor) BETWEEN 1 AND 256),
    revision varchar(128),
    snapshot_digest char(64) CHECK (snapshot_digest IS NULL OR snapshot_digest ~ '^[0-9a-f]{64}$'),
    head_version bigint CHECK (head_version IS NULL OR head_version >= 1),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

CREATE TABLE rain_i18n.i18n_change (
    cursor bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope varchar(128) NOT NULL CHECK (scope ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    kind varchar(32) NOT NULL CHECK (kind IN ('HEAD_CHANGED', 'RELEASE_PRUNED')),
    revision varchar(128) NOT NULL CHECK (octet_length(revision) BETWEEN 1 AND 128),
    snapshot_digest char(64) NOT NULL CHECK (snapshot_digest ~ '^[0-9a-f]{64}$'),
    head_version bigint CHECK (head_version IS NULL OR head_version >= 1),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT i18n_change_release_fk FOREIGN KEY (scope, revision, snapshot_digest)
        REFERENCES rain_i18n.i18n_release (scope, revision, snapshot_digest)
);

CREATE INDEX i18n_change_cursor_idx
    ON rain_i18n.i18n_change (cursor)
    INCLUDE (scope, kind, revision, snapshot_digest, head_version, recorded_at);

CREATE OR REPLACE FUNCTION rain_i18n.refuse_immutable_catalog_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'immutable rain_i18n row cannot be changed' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER i18n_artifact_immutable
    BEFORE UPDATE OR DELETE ON rain_i18n.i18n_artifact
    FOR EACH ROW EXECUTE FUNCTION rain_i18n.refuse_immutable_catalog_mutation();

CREATE TRIGGER i18n_release_immutable
    BEFORE UPDATE OR DELETE ON rain_i18n.i18n_release
    FOR EACH ROW EXECUTE FUNCTION rain_i18n.refuse_immutable_catalog_mutation();

CREATE TRIGGER i18n_audit_immutable
    BEFORE UPDATE OR DELETE ON rain_i18n.i18n_audit
    FOR EACH ROW EXECUTE FUNCTION rain_i18n.refuse_immutable_catalog_mutation();

CREATE TRIGGER i18n_change_immutable
    BEFORE UPDATE OR DELETE ON rain_i18n.i18n_change
    FOR EACH ROW EXECUTE FUNCTION rain_i18n.refuse_immutable_catalog_mutation();
