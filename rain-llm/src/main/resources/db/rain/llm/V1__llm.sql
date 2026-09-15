-- Cluster-wide admission to a model server.
--
-- A pool is one shared budget: `taken` counts the slots held against it. Pool rows are not seeded here;
-- they are created from `rain.llm.pools.<name>` when a process starts, and a pool with no row is refused
-- explicitly at call time.
CREATE TABLE llm_budget (
  pool  TEXT PRIMARY KEY,
  taken INTEGER NOT NULL DEFAULT 0,
  CONSTRAINT llm_budget_taken_is_not_negative CHECK (taken >= 0)
);

-- One row per held slot. The lease is written with the database server's clock, so no two processes
-- compare their own clocks; the next acquiring statement for the pool sweeps what has expired and repairs
-- `taken` in the same statement.
CREATE TABLE llm_slots (
  id         UUID PRIMARY KEY,
  pool       TEXT NOT NULL REFERENCES llm_budget (pool),
  holder     TEXT NOT NULL,
  taken_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL
);

-- The sweep reads one pool's expired slots, and only those.
CREATE INDEX ix_llm_slots_expiry ON llm_slots (pool, expires_at);
