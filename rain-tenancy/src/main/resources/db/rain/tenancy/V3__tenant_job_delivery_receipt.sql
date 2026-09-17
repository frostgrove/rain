-- The tenant database source row is acknowledged separately, so this control-plane receipt is the
-- durable exactly-once bridge from a stable local-outbox id to a central rain-jobs invocation.
-- A claim and the queue enqueue share one control-plane transaction; an incomplete receipt cannot commit.
CREATE TABLE tenant_delivery_receipt (
  outbox_id       UUID        PRIMARY KEY,
  ref_digest      BYTEA       NOT NULL CHECK (octet_length(ref_digest) = 32),
  tenant_epoch    BIGINT      NOT NULL CHECK (tenant_epoch > 0),
  definition      TEXT        NOT NULL CHECK (definition ~ '^[a-z][a-z0-9.-]{0,127}$'),
  payload_digest  BYTEA       NOT NULL CHECK (octet_length(payload_digest) = 32),
  invocation_id   UUID,
  dispatched_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_tenant_delivery_receipt_dispatched
  ON tenant_delivery_receipt (dispatched_at, outbox_id);

-- `claim → enqueue → complete` is one transaction. PostgreSQL cannot defer NOT NULL, so this
-- deferred constraint trigger validates the final row image at commit instead of rejecting the
-- short-lived claim row before the queue can return its invocation id.
CREATE OR REPLACE FUNCTION require_completed_tenant_delivery_receipt()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM rain_tenancy.tenant_delivery_receipt
    WHERE outbox_id = NEW.outbox_id
      AND invocation_id IS NULL
  ) THEN
    RAISE EXCEPTION 'tenant delivery receipt must have an invocation before commit';
  END IF;
  RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER tenant_delivery_receipt_completed
  AFTER INSERT OR UPDATE OF invocation_id ON tenant_delivery_receipt
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION require_completed_tenant_delivery_receipt();
