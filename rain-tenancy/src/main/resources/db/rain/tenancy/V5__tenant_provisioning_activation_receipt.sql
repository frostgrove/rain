-- `activate` is a legal lifecycle edge only after a configured provisioning graph has durable
-- success evidence. Preserve its distinct replayable outcome rather than disguising it as an
-- illegal graph transition or a version conflict.
ALTER TABLE tenant_operation_receipt
  DROP CONSTRAINT tenant_operation_receipt_outcome_check;

ALTER TABLE tenant_operation_receipt
  ADD CONSTRAINT ck_tenant_operation_receipt_outcome CHECK (
    outcome IN ('created', 'transitioned', 'version_conflict', 'not_found', 'transition_refused', 'provisioning_incomplete')
  );

ALTER TABLE tenant_operation_receipt
  DROP CONSTRAINT ck_tenant_operation_receipt_snapshot_complete;

ALTER TABLE tenant_operation_receipt
  ADD CONSTRAINT ck_tenant_operation_receipt_snapshot_complete CHECK (
    (outcome IN ('created', 'transitioned', 'version_conflict', 'transition_refused', 'provisioning_incomplete')
      AND lifecycle IS NOT NULL AND epoch IS NOT NULL AND placement_version IS NOT NULL AND row_version IS NOT NULL)
    OR (outcome = 'not_found' AND lifecycle IS NULL AND epoch IS NULL AND placement_version IS NULL AND row_version IS NULL)
  );
