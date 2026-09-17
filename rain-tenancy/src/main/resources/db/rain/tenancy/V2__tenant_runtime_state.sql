-- Runtime state is versioned separately from lifecycle so cache invalidation and declared settings
-- never require a broad tenant-row update or a cache-key scan.
CREATE TABLE tenant_runtime_state (
  ref_digest       BYTEA       PRIMARY KEY REFERENCES tenant(ref_digest) ON DELETE RESTRICT CHECK (octet_length(ref_digest) = 32),
  epoch            BIGINT      NOT NULL CHECK (epoch > 0),
  cache_generation BIGINT      NOT NULL DEFAULT 0 CHECK (cache_generation >= 0),
  settings_version BIGINT      NOT NULL DEFAULT 1 CHECK (settings_version > 0),
  row_version      BIGINT      NOT NULL DEFAULT 1 CHECK (row_version > 0),
  updated_at       TIMESTAMPTZ NOT NULL
);

CREATE TABLE tenant_runtime_setting (
  ref_digest       BYTEA       NOT NULL REFERENCES tenant_runtime_state(ref_digest) ON DELETE RESTRICT CHECK (octet_length(ref_digest) = 32),
  setting_key      TEXT        NOT NULL CHECK (setting_key ~ '^[a-z][a-z0-9_.-]{0,127}$'),
  canonical_value  BYTEA,
  secret_ref       TEXT,
  value_version    BIGINT      NOT NULL CHECK (value_version > 0),
  updated_at       TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (ref_digest, setting_key),
  CONSTRAINT ck_tenant_runtime_setting_shape CHECK (
    (canonical_value IS NOT NULL AND octet_length(canonical_value) <= 65536 AND secret_ref IS NULL)
    OR (canonical_value IS NULL AND secret_ref IS NOT NULL AND octet_length(secret_ref) BETWEEN 1 AND 512)
  )
);

INSERT INTO tenant_runtime_state (ref_digest, epoch, cache_generation, settings_version, row_version, updated_at)
SELECT ref_digest, epoch, 0, 1, 1, updated_at
FROM tenant;

-- Runtime commands get their own receipt shape because their repeat answer includes state versions,
-- not a lifecycle transition. The control row is still locked in the same transaction.
CREATE TABLE tenant_runtime_operation_receipt (
  operation_id      UUID        PRIMARY KEY,
  fingerprint       BYTEA       NOT NULL CHECK (octet_length(fingerprint) = 32),
  ref_digest        BYTEA       NOT NULL CHECK (octet_length(ref_digest) = 32),
  action            TEXT        NOT NULL CHECK (action IN ('update_runtime_settings', 'invalidate_tenant_cache')),
  outcome           TEXT        NOT NULL CHECK (outcome IN ('applied', 'version_conflict', 'not_found', 'inactive')),
  epoch             BIGINT      CHECK (epoch IS NULL OR epoch > 0),
  control_version   BIGINT      CHECK (control_version IS NULL OR control_version > 0),
  cache_generation  BIGINT      CHECK (cache_generation IS NULL OR cache_generation >= 0),
  settings_version  BIGINT      CHECK (settings_version IS NULL OR settings_version > 0),
  runtime_version   BIGINT      CHECK (runtime_version IS NULL OR runtime_version > 0),
  completed_at      TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_tenant_runtime_operation_receipt_shape CHECK (
    (outcome IN ('applied', 'version_conflict', 'inactive')
      AND epoch IS NOT NULL AND control_version IS NOT NULL AND cache_generation IS NOT NULL
      AND settings_version IS NOT NULL AND runtime_version IS NOT NULL)
    OR (outcome = 'not_found'
      AND epoch IS NULL AND control_version IS NULL AND cache_generation IS NULL
      AND settings_version IS NULL AND runtime_version IS NULL)
  )
);

-- A restore gets a new epoch, so prior cache namespaces and settings cannot become valid again.
CREATE OR REPLACE FUNCTION reset_tenant_runtime_state()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  INSERT INTO rain_tenancy.tenant_runtime_state (ref_digest, epoch, cache_generation, settings_version, row_version, updated_at)
  VALUES (NEW.ref_digest, NEW.epoch, 0, 1, 1, NEW.updated_at)
  ON CONFLICT (ref_digest) DO UPDATE
    SET epoch = EXCLUDED.epoch,
        cache_generation = 0,
        settings_version = 1,
        row_version = rain_tenancy.tenant_runtime_state.row_version + 1,
        updated_at = EXCLUDED.updated_at;
  DELETE FROM rain_tenancy.tenant_runtime_setting WHERE ref_digest = NEW.ref_digest;
  RETURN NEW;
END;
$$;

CREATE TRIGGER tenant_runtime_state_on_create
  AFTER INSERT ON tenant
  FOR EACH ROW EXECUTE FUNCTION reset_tenant_runtime_state();

CREATE TRIGGER tenant_runtime_state_on_restore
  AFTER UPDATE OF epoch ON tenant
  FOR EACH ROW
  WHEN (OLD.epoch IS DISTINCT FROM NEW.epoch)
  EXECUTE FUNCTION reset_tenant_runtime_state();
