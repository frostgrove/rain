-- An application table that references a module table: it can only be created once the module schema exists.
CREATE TABLE orders (
  id        UUID PRIMARY KEY,
  widget_id UUID NOT NULL REFERENCES rain_alpha.widgets (id)
);
