CREATE SCHEMA IF NOT EXISTS rain_tenancy_i18n;

CREATE TABLE rain_tenancy_i18n.tenancy_i18n_schema_meta (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    schema_version integer NOT NULL CHECK (schema_version = 1),
    schema_fingerprint bytea NOT NULL CHECK (octet_length(schema_fingerprint) = 32),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

INSERT INTO rain_tenancy_i18n.tenancy_i18n_schema_meta (schema_version, schema_fingerprint)
VALUES (1, decode(repeat('00', 32), 'hex'));

CREATE TABLE rain_tenancy_i18n.tenant_overlay_revision (
    tenant_namespace bytea NOT NULL CHECK (octet_length(tenant_namespace) = 32),
    tenant_epoch bigint NOT NULL CHECK (tenant_epoch >= 1),
    revision varchar(128) NOT NULL CHECK (octet_length(revision) BETWEEN 1 AND 128),
    base_revision varchar(128) NOT NULL CHECK (octet_length(base_revision) BETWEEN 1 AND 128),
    base_digest char(64) NOT NULL CHECK (base_digest ~ '^[0-9a-f]{64}$'),
    state varchar(16) NOT NULL CHECK (state IN ('DRAFT', 'REVIEWED')),
    overlay_digest char(64) CHECK (overlay_digest IS NULL OR overlay_digest ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    reviewed_at timestamptz,
    PRIMARY KEY (tenant_namespace, tenant_epoch, revision),
    CHECK ((state = 'DRAFT' AND overlay_digest IS NULL AND reviewed_at IS NULL)
        OR (state = 'REVIEWED' AND overlay_digest IS NOT NULL AND reviewed_at IS NOT NULL))
);

CREATE TABLE rain_tenancy_i18n.tenant_overlay_entry (
    tenant_namespace bytea NOT NULL CHECK (octet_length(tenant_namespace) = 32),
    tenant_epoch bigint NOT NULL CHECK (tenant_epoch >= 1),
    revision varchar(128) NOT NULL CHECK (octet_length(revision) BETWEEN 1 AND 128),
    entry_index integer NOT NULL CHECK (entry_index >= 0),
    message_key varchar(256) NOT NULL CHECK (octet_length(message_key) BETWEEN 1 AND 256),
    locale varchar(128) NOT NULL CHECK (octet_length(locale) BETWEEN 1 AND 128),
    template text NOT NULL CHECK (octet_length(template) BETWEEN 1 AND 1048576),
    contract_revision integer NOT NULL CHECK (contract_revision >= 1),
    contract_digest char(64) NOT NULL CHECK (contract_digest ~ '^[0-9a-f]{64}$'),
    source_digest char(64) NOT NULL CHECK (source_digest ~ '^[0-9a-f]{64}$'),
    review_digest char(64) NOT NULL CHECK (review_digest ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (tenant_namespace, tenant_epoch, revision, entry_index),
    FOREIGN KEY (tenant_namespace, tenant_epoch, revision)
        REFERENCES rain_tenancy_i18n.tenant_overlay_revision (tenant_namespace, tenant_epoch, revision)
);

CREATE TABLE rain_tenancy_i18n.tenant_overlay_head (
    tenant_namespace bytea NOT NULL CHECK (octet_length(tenant_namespace) = 32),
    tenant_epoch bigint NOT NULL CHECK (tenant_epoch >= 1),
    version bigint NOT NULL CHECK (version >= 1),
    active_revision varchar(128),
    active_digest char(64) CHECK (active_digest IS NULL OR active_digest ~ '^[0-9a-f]{64}$'),
    changed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (tenant_namespace, tenant_epoch),
    FOREIGN KEY (tenant_namespace, tenant_epoch, active_revision)
        REFERENCES rain_tenancy_i18n.tenant_overlay_revision (tenant_namespace, tenant_epoch, revision),
    CHECK ((active_revision IS NULL AND active_digest IS NULL) OR (active_revision IS NOT NULL AND active_digest IS NOT NULL))
);

CREATE TABLE rain_tenancy_i18n.tenant_overlay_receipt (
    tenant_namespace bytea NOT NULL CHECK (octet_length(tenant_namespace) = 32),
    tenant_epoch bigint NOT NULL CHECK (tenant_epoch >= 1),
    operation_id uuid NOT NULL,
    fingerprint bytea NOT NULL CHECK (octet_length(fingerprint) = 32),
    outcome_kind varchar(16) NOT NULL CHECK (outcome_kind IN ('CHANGED', 'CONFLICT', 'MISSING', 'INVALID')),
    version bigint CHECK (version IS NULL OR version >= 0),
    revision varchar(128),
    active_revision varchar(128),
    active_digest char(64) CHECK (active_digest IS NULL OR active_digest ~ '^[0-9a-f]{64}$'),
    problems jsonb,
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (tenant_namespace, tenant_epoch, operation_id),
    CHECK ((outcome_kind = 'CHANGED' AND version IS NOT NULL AND problems IS NULL)
        OR (outcome_kind = 'CONFLICT' AND version IS NOT NULL AND revision IS NULL AND problems IS NULL)
        OR (outcome_kind = 'MISSING' AND revision IS NOT NULL AND version IS NULL AND problems IS NULL)
        OR (outcome_kind = 'INVALID' AND problems IS NOT NULL AND version IS NULL))
);

CREATE TABLE rain_tenancy_i18n.tenant_overlay_receipt_problem (
    tenant_namespace bytea NOT NULL CHECK (octet_length(tenant_namespace) = 32),
    tenant_epoch bigint NOT NULL CHECK (tenant_epoch >= 1),
    operation_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    path varchar(512) NOT NULL CHECK (octet_length(path) BETWEEN 1 AND 512),
    message varchar(2048) NOT NULL CHECK (octet_length(message) BETWEEN 1 AND 2048),
    PRIMARY KEY (tenant_namespace, tenant_epoch, operation_id, ordinal),
    FOREIGN KEY (tenant_namespace, tenant_epoch, operation_id)
        REFERENCES rain_tenancy_i18n.tenant_overlay_receipt (tenant_namespace, tenant_epoch, operation_id)
);

CREATE TABLE rain_tenancy_i18n.tenant_overlay_audit (
    audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_namespace bytea NOT NULL CHECK (octet_length(tenant_namespace) = 32),
    tenant_epoch bigint NOT NULL CHECK (tenant_epoch >= 1),
    operation_id uuid NOT NULL,
    actor varchar(256) NOT NULL CHECK (octet_length(actor) BETWEEN 1 AND 256),
    kind varchar(16) NOT NULL CHECK (kind IN ('STAGED', 'REVIEWED', 'ACTIVATED', 'ROLLED_BACK')),
    revision varchar(128) NOT NULL CHECK (octet_length(revision) BETWEEN 1 AND 128),
    version bigint NOT NULL CHECK (version >= 1),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

CREATE INDEX tenant_overlay_audit_scope_idx
    ON rain_tenancy_i18n.tenant_overlay_audit (tenant_namespace, tenant_epoch, audit_id);

CREATE TABLE rain_tenancy_i18n.tenant_overlay_change (
    cursor bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_namespace bytea NOT NULL CHECK (octet_length(tenant_namespace) = 32),
    tenant_epoch bigint NOT NULL CHECK (tenant_epoch >= 1),
    kind varchar(16) NOT NULL CHECK (kind IN ('STAGED', 'REVIEWED', 'ACTIVATED', 'ROLLED_BACK')),
    active_revision varchar(128),
    active_digest char(64) CHECK (active_digest IS NULL OR active_digest ~ '^[0-9a-f]{64}$'),
    version bigint NOT NULL CHECK (version >= 1),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CHECK ((active_revision IS NULL AND active_digest IS NULL) OR (active_revision IS NOT NULL AND active_digest IS NOT NULL))
);

CREATE INDEX tenant_overlay_change_cursor_idx
    ON rain_tenancy_i18n.tenant_overlay_change (cursor)
    INCLUDE (tenant_namespace, tenant_epoch, kind, active_revision, active_digest, version, recorded_at);

CREATE OR REPLACE FUNCTION rain_tenancy_i18n.refuse_immutable_tenant_overlay_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'immutable rain_tenancy_i18n row cannot be changed' USING ERRCODE = '55000';
END;
$$;

CREATE OR REPLACE FUNCTION rain_tenancy_i18n.allow_only_overlay_review()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.state = 'DRAFT'
       AND NEW.state = 'REVIEWED'
       AND NEW.tenant_namespace = OLD.tenant_namespace
       AND NEW.tenant_epoch = OLD.tenant_epoch
       AND NEW.revision = OLD.revision
       AND NEW.base_revision = OLD.base_revision
       AND NEW.base_digest = OLD.base_digest
       AND NEW.created_at = OLD.created_at
       AND OLD.overlay_digest IS NULL
       AND OLD.reviewed_at IS NULL
       AND NEW.overlay_digest IS NOT NULL
       AND NEW.reviewed_at IS NOT NULL
    THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'tenant overlay revision is immutable except for one draft-to-reviewed transition' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER tenant_overlay_revision_review_once
    BEFORE UPDATE OR DELETE ON rain_tenancy_i18n.tenant_overlay_revision
    FOR EACH ROW EXECUTE FUNCTION rain_tenancy_i18n.allow_only_overlay_review();

CREATE TRIGGER tenant_overlay_entry_immutable
    BEFORE UPDATE OR DELETE ON rain_tenancy_i18n.tenant_overlay_entry
    FOR EACH ROW EXECUTE FUNCTION rain_tenancy_i18n.refuse_immutable_tenant_overlay_mutation();

CREATE TRIGGER tenant_overlay_audit_immutable
    BEFORE UPDATE OR DELETE ON rain_tenancy_i18n.tenant_overlay_audit
    FOR EACH ROW EXECUTE FUNCTION rain_tenancy_i18n.refuse_immutable_tenant_overlay_mutation();

CREATE TRIGGER tenant_overlay_change_immutable
    BEFORE UPDATE OR DELETE ON rain_tenancy_i18n.tenant_overlay_change
    FOR EACH ROW EXECUTE FUNCTION rain_tenancy_i18n.refuse_immutable_tenant_overlay_mutation();

CREATE TRIGGER tenant_overlay_receipt_immutable
    BEFORE UPDATE OR DELETE ON rain_tenancy_i18n.tenant_overlay_receipt
    FOR EACH ROW EXECUTE FUNCTION rain_tenancy_i18n.refuse_immutable_tenant_overlay_mutation();

CREATE TRIGGER tenant_overlay_receipt_problem_immutable
    BEFORE UPDATE OR DELETE ON rain_tenancy_i18n.tenant_overlay_receipt_problem
    FOR EACH ROW EXECUTE FUNCTION rain_tenancy_i18n.refuse_immutable_tenant_overlay_mutation();
