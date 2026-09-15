-- rain-access: the permission catalogue, roles, grants, password credentials and sessions.
--
-- A grant, a credential and a session point at a subject — a type and an id — never at an application
-- table, so any kind of caller needs a directory and no migration here. Nothing references a subject
-- table and nothing cascades: every removal the application makes is a bounded batch it issues itself,
-- because one cascading statement over a role held by millions of subjects is not bounded by anything.
--
-- No column carries a database default for time or identity: the application mints UUIDv7 ids and
-- takes every instant from its injected clock.

-- The catalogue. One row per declared permission code, written by the start-up synchronisation.
CREATE TABLE permissions (
  id         UUID PRIMARY KEY,
  code       TEXT NOT NULL,
  name       TEXT NOT NULL,
  module     TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_permissions_code ON permissions (code);

-- A system role is declared by the application and refuses renames, deletes and detaches. A role that
-- grants every permission holds no role_permissions rows: the flag is read by the permission check, so a
-- permission declared later is held without writing a row per role.
CREATE TABLE roles (
  id                      UUID PRIMARY KEY,
  slug                    TEXT NOT NULL,
  name                    TEXT NOT NULL,
  is_system               BOOLEAN NOT NULL,
  grants_every_permission BOOLEAN NOT NULL,
  created_at              TIMESTAMPTZ NOT NULL,
  CONSTRAINT roles_every_permission_is_system CHECK (NOT grants_every_permission OR is_system)
);
CREATE UNIQUE INDEX uq_roles_slug ON roles (slug);
CREATE INDEX ix_roles_every_permission ON roles (id) WHERE grants_every_permission;

CREATE TABLE role_permissions (
  role_id       UUID NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
  permission_id UUID NOT NULL REFERENCES permissions (id) ON DELETE RESTRICT,
  attached_at   TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX ix_role_permissions_permission ON role_permissions (permission_id, role_id);

CREATE TABLE subject_roles (
  subject_type TEXT NOT NULL,
  subject_id   UUID NOT NULL,
  role_id      UUID NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
  granted_at   TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_subject_roles PRIMARY KEY (subject_type, subject_id, role_id)
);
-- Holders of one role, a keyset page at a time.
CREATE INDEX ix_subject_roles_role ON subject_roles (role_id, subject_type, subject_id);

CREATE TABLE subject_permissions (
  subject_type  TEXT NOT NULL,
  subject_id    UUID NOT NULL,
  permission_id UUID NOT NULL REFERENCES permissions (id) ON DELETE RESTRICT,
  granted_at    TIMESTAMPTZ NOT NULL,
  CONSTRAINT pk_subject_permissions PRIMARY KEY (subject_type, subject_id, permission_id)
);

-- Which role a self-registered subject of one type is given.
CREATE TABLE subject_default_roles (
  subject_type TEXT PRIMARY KEY,
  role_id      UUID NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
  updated_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_subject_default_roles_role ON subject_default_roles (role_id);

-- A password credential. The identifier is stored as the subject's declared normalisation produced it;
-- version guards every write, so a hash verified outside a transaction is only acted on while unchanged.
CREATE TABLE credentials (
  id           UUID PRIMARY KEY,
  subject_type TEXT NOT NULL,
  subject_id   UUID NOT NULL,
  provider     TEXT NOT NULL CHECK (provider IN ('password')),
  identifier   TEXT NOT NULL,
  secret_hash  TEXT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL,
  version      BIGINT NOT NULL CHECK (version >= 0)
);
CREATE UNIQUE INDEX uq_credentials_identifier ON credentials (subject_type, provider, identifier);
CREATE UNIQUE INDEX uq_credentials_subject_password ON credentials (subject_type, subject_id) WHERE provider = 'password';

-- One sign-in and the lineage of refresh credentials it issued; digests only, never a credential.
CREATE TABLE sessions (
  id                  UUID PRIMARY KEY,
  subject_type        TEXT NOT NULL,
  subject_id          UUID NOT NULL,
  token_hash          TEXT NOT NULL,
  previous_token_hash TEXT,
  generation          BIGINT NOT NULL CHECK (generation >= 1),
  user_agent          TEXT,
  address             TEXT,
  created_at          TIMESTAMPTZ NOT NULL,
  last_used_at        TIMESTAMPTZ NOT NULL,
  rotated_at          TIMESTAMPTZ,
  expires_at          TIMESTAMPTZ NOT NULL,
  revoked_at          TIMESTAMPTZ,
  revoked_reason      TEXT,
  CONSTRAINT sessions_revocation_complete CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
  CONSTRAINT sessions_rotation_complete CHECK ((rotated_at IS NULL) = (previous_token_hash IS NULL))
);
CREATE UNIQUE INDEX uq_sessions_token_hash ON sessions (token_hash);
CREATE INDEX ix_sessions_previous_token_hash ON sessions (previous_token_hash) WHERE previous_token_hash IS NOT NULL;
-- A subject's live sessions, a keyset page at a time and newest first; the lifetime columns are key
-- columns so the expiry and idle bounds are index conditions rather than a filter over fetched rows.
CREATE INDEX ix_sessions_live ON sessions (subject_type, subject_id, created_at, id, expires_at, last_used_at)
  WHERE revoked_at IS NULL;
-- The revocation journal replay reads, and retention of revoked sessions.
CREATE INDEX ix_sessions_revoked ON sessions (revoked_at, id) WHERE revoked_at IS NOT NULL;
-- Retention of expired sessions.
CREATE INDEX ix_sessions_expired ON sessions (expires_at, id);

-- "Every session of this subject issued up to this instant is closed", written once by a sign-out
-- everywhere or a password change, so closing a subject's sessions costs one row and one revocation key
-- whatever the number of sessions. kept_session_id is the one session the closing caller keeps.
CREATE TABLE subject_cutoffs (
  subject_type    TEXT NOT NULL,
  subject_id      UUID NOT NULL,
  cutoff_at       TIMESTAMPTZ NOT NULL,
  kept_session_id UUID,
  CONSTRAINT pk_subject_cutoffs PRIMARY KEY (subject_type, subject_id)
);
CREATE INDEX ix_subject_cutoffs_cutoff ON subject_cutoffs (cutoff_at, subject_type, subject_id);
