-- The control plane stores only a keyed digest of an application tenant reference.  The raw
-- reference remains at the resolver boundary and never becomes an operational table key.
CREATE TABLE tenant (
  ref_digest        BYTEA       PRIMARY KEY CHECK (octet_length(ref_digest) = 32),
  lifecycle         TEXT        NOT NULL CHECK (lifecycle IN ('provisioning', 'active', 'read_only', 'suspended', 'migrating', 'deleting', 'deleted')),
  epoch             BIGINT      NOT NULL CHECK (epoch > 0),
  placement_version BIGINT      NOT NULL CHECK (placement_version > 0),
  row_version       BIGINT      NOT NULL CHECK (row_version > 0),
  created_at        TIMESTAMPTZ NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL
);

-- The immutable transition log is evidence of a successful, fenced control mutation.  A same-state
-- begin-provision or placement rotation is still explicit evidence and never an unlogged upsert.
CREATE TABLE tenant_transition (
  id               UUID        PRIMARY KEY,
  operation_id     UUID        NOT NULL UNIQUE,
  ref_digest       BYTEA       NOT NULL CHECK (octet_length(ref_digest) = 32),
  action           TEXT        NOT NULL CHECK (action IN ('create_draft', 'begin_provision', 'activate', 'make_read_only', 'resume_writes', 'suspend', 'begin_migration', 'finish_migration', 'begin_deletion', 'tombstone', 'restore_new_epoch', 'rotate_placement')),
  from_lifecycle   TEXT        CHECK (from_lifecycle IS NULL OR from_lifecycle IN ('provisioning', 'active', 'read_only', 'suspended', 'migrating', 'deleting', 'deleted')),
  to_lifecycle     TEXT        NOT NULL CHECK (to_lifecycle IN ('provisioning', 'active', 'read_only', 'suspended', 'migrating', 'deleting', 'deleted')),
  expected_version BIGINT      NOT NULL CHECK (expected_version >= 0),
  new_version      BIGINT      NOT NULL CHECK (new_version > 0),
  epoch            BIGINT      NOT NULL CHECK (epoch > 0),
  placement_version BIGINT     NOT NULL CHECK (placement_version > 0),
  occurred_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_tenant_transition_ref_occurred ON tenant_transition (ref_digest, occurred_at DESC, id DESC);

-- Claiming an operation before a tenant row is read gives exactly one committed outcome to a retry.
-- Its fingerprint is deliberately independent of row version so a reused id cannot retarget a tenant.
CREATE TABLE tenant_operation_receipt (
  operation_id     UUID        PRIMARY KEY,
  fingerprint       BYTEA       NOT NULL CHECK (octet_length(fingerprint) = 32),
  ref_digest        BYTEA       NOT NULL CHECK (octet_length(ref_digest) = 32),
  action            TEXT        NOT NULL CHECK (action IN ('create_draft', 'begin_provision', 'activate', 'make_read_only', 'resume_writes', 'suspend', 'begin_migration', 'finish_migration', 'begin_deletion', 'tombstone', 'restore_new_epoch', 'rotate_placement')),
  outcome           TEXT        NOT NULL CHECK (outcome IN ('created', 'transitioned', 'version_conflict', 'not_found', 'transition_refused')),
  lifecycle         TEXT        CHECK (lifecycle IS NULL OR lifecycle IN ('provisioning', 'active', 'read_only', 'suspended', 'migrating', 'deleting', 'deleted')),
  epoch             BIGINT      CHECK (epoch IS NULL OR epoch > 0),
  placement_version BIGINT      CHECK (placement_version IS NULL OR placement_version > 0),
  row_version       BIGINT      CHECK (row_version IS NULL OR row_version > 0),
  completed_at      TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_tenant_operation_receipt_snapshot_complete CHECK (
    (outcome IN ('created', 'transitioned', 'version_conflict', 'transition_refused') AND lifecycle IS NOT NULL AND epoch IS NOT NULL AND placement_version IS NOT NULL AND row_version IS NOT NULL)
    OR (outcome = 'not_found' AND lifecycle IS NULL AND epoch IS NULL AND placement_version IS NULL AND row_version IS NULL)
  )
);
