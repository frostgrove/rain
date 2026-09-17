-- Scoped evidence is an opaque, epoch-fenced partition. Existing central rows remain valid with
-- all three values null; an application never writes a raw tenant reference into this table.
ALTER TABLE audit_log
  ADD COLUMN scope_kind TEXT,
  ADD COLUMN scope_digest BYTEA,
  ADD COLUMN scope_epoch BIGINT;

ALTER TABLE audit_log
  ADD CONSTRAINT ck_audit_log_scope_shape
    CHECK (
      (scope_kind IS NULL AND scope_digest IS NULL AND scope_epoch IS NULL)
      OR (scope_kind IS NOT NULL AND scope_digest IS NOT NULL AND scope_epoch IS NOT NULL AND scope_epoch > 0)
    ),
  ADD CONSTRAINT ck_audit_log_scope_digest
    CHECK (scope_digest IS NULL OR octet_length(scope_digest) BETWEEN 1 AND 64);

CREATE INDEX ix_audit_log_scope_resource
  ON audit_log (scope_kind, scope_digest, scope_epoch, resource_kind, resource_id, occurred_at DESC, id DESC)
  WHERE scope_kind IS NOT NULL;

CREATE INDEX ix_audit_log_scope_actor
  ON audit_log (scope_kind, scope_digest, scope_epoch, actor_type, actor_id, occurred_at DESC, id DESC)
  WHERE scope_kind IS NOT NULL;
