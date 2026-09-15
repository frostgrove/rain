CREATE TABLE notes (
  id         UUID PRIMARY KEY,
  status     TEXT NOT NULL CHECK (status IN ('draft', 'published')),
  body       JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  version    INT NOT NULL
);
