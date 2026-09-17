CREATE TABLE products (
  id         UUID PRIMARY KEY,
  name       TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  sku        TEXT NOT NULL CHECK (sku <> ''),
  category   TEXT NOT NULL CHECK (category <> ''),
  price      NUMERIC(12,2) NOT NULL CHECK (price >= 0),
  stock      INTEGER NOT NULL CHECK (stock >= 0),
  status     TEXT NOT NULL CHECK (status IN ('active', 'draft', 'archived')),
  supplier   TEXT NOT NULL CHECK (supplier <> ''),
  notes      TEXT,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_products_sku ON products (sku);
CREATE INDEX ix_products_name ON products (name, id);
CREATE INDEX ix_products_category ON products (category, id);
CREATE INDEX ix_products_price ON products (price, id);
CREATE INDEX ix_products_stock ON products (stock, id);
CREATE INDEX ix_products_status ON products (status, id);
CREATE INDEX ix_products_category_status ON products (category, status, id);
