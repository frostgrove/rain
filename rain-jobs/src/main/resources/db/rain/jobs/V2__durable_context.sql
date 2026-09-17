-- The context envelope is owned by rain-jobs. Provider fragments inside context_bytes are opaque
-- to this schema and independently versioned; rows created before this migration remain unscoped.
ALTER TABLE job_invocation
  ADD COLUMN context_version SMALLINT,
  ADD COLUMN context_bytes BYTEA,
  ADD COLUMN producer_partition BYTEA,
  ADD COLUMN payload_digest BYTEA;

ALTER TABLE job_invocation
  ADD CONSTRAINT ck_job_invocation_context_shape
    CHECK (
      (context_version IS NULL AND context_bytes IS NULL)
      OR (context_version IS NOT NULL AND context_bytes IS NOT NULL AND context_version > 0)
    ),
  ADD CONSTRAINT ck_job_invocation_context_bytes
    CHECK (context_bytes IS NULL OR octet_length(context_bytes) BETWEEN 1 AND 16384),
  ADD CONSTRAINT ck_job_invocation_producer_partition
    CHECK (producer_partition IS NULL OR octet_length(producer_partition) BETWEEN 1 AND 128),
  ADD CONSTRAINT ck_job_invocation_payload_digest
    CHECK (payload_digest IS NULL OR octet_length(payload_digest) = 32);
