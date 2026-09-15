-- The audit trail. Append-only: there is no update path and no version column, because a row that can be
-- rewritten states nothing. The actor is a (type, id) pair with no foreign key, so any kind of subject —
-- a person, a service account — can be recorded, and deleting a subject never deletes its evidence.
CREATE TABLE audit_log (
  id            UUID PRIMARY KEY,
  occurred_at   TIMESTAMPTZ NOT NULL,
  actor_type    TEXT,
  actor_id      TEXT,
  request_id    TEXT,
  module        TEXT NOT NULL,
  action        TEXT NOT NULL,
  resource_kind TEXT NOT NULL,
  resource_id   TEXT,
  outcome       TEXT NOT NULL CHECK (outcome IN ('ok', 'refused', 'failed')),
  detail        JSONB NOT NULL,
  CONSTRAINT audit_log_actor_complete CHECK ((actor_type IS NULL) = (actor_id IS NULL))
);

-- Both reads are keyset pages over (occurred_at, id) within one resource or one actor.
CREATE INDEX ix_audit_log_resource ON audit_log (resource_kind, resource_id, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_log_actor ON audit_log (actor_type, actor_id, occurred_at DESC, id DESC);
