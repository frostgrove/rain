-- An in-flight epoch has one immutable declared custom-step graph. A deployment cannot silently
-- reinterpret durable completion evidence after changing a step id, revision, or dependency edge.
CREATE TABLE tenant_provision_workflow (
  ref_digest        BYTEA       NOT NULL REFERENCES tenant(ref_digest) ON DELETE RESTRICT CHECK (octet_length(ref_digest) = 32),
  tenant_epoch      BIGINT      NOT NULL CHECK (tenant_epoch > 0),
  placement_version BIGINT      NOT NULL CHECK (placement_version > 0),
  workflow_fingerprint BYTEA    NOT NULL CHECK (octet_length(workflow_fingerprint) = 32),
  created_at        TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (ref_digest, tenant_epoch)
);

-- A lease may expire while an external idempotent step is running. The monotonically increasing
-- fence plus opaque lease token ensures a stale worker cannot complete or quarantine a new claim.
CREATE TABLE tenant_provision_step (
  ref_digest        BYTEA       NOT NULL,
  tenant_epoch      BIGINT      NOT NULL,
  step_id           TEXT        NOT NULL CHECK (step_id ~ '^[a-z][a-z0-9_.-]{0,127}$'),
  state             TEXT        NOT NULL CHECK (state IN ('pending', 'running', 'succeeded', 'failed')),
  fence             BIGINT      NOT NULL CHECK (fence >= 0),
  lease_token       UUID,
  lease_until       TIMESTAMPTZ,
  attempts          INTEGER     NOT NULL CHECK (attempts >= 0),
  last_failure_code TEXT        CHECK (last_failure_code IS NULL OR last_failure_code ~ '^[a-z][a-z0-9_.-]{0,63}$'),
  updated_at        TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (ref_digest, tenant_epoch, step_id),
  FOREIGN KEY (ref_digest, tenant_epoch)
    REFERENCES tenant_provision_workflow (ref_digest, tenant_epoch) ON DELETE RESTRICT,
  CONSTRAINT ck_tenant_provision_step_running_shape CHECK (
    (state = 'running' AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND last_failure_code IS NULL) OR
    (state = 'pending' AND lease_token IS NULL AND lease_until IS NULL) OR
    (state = 'succeeded' AND lease_token IS NULL AND lease_until IS NULL AND last_failure_code IS NULL) OR
    (state = 'failed' AND lease_token IS NULL AND lease_until IS NULL AND last_failure_code IS NOT NULL)
  )
);

CREATE INDEX ix_tenant_provision_step_lease
  ON tenant_provision_step (state, lease_until, updated_at);
