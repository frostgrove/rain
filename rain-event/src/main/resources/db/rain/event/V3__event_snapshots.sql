-- Snapshots are disposable, versioned replay optimizations. They never replace source records and
-- may be deleted in bounded batches by the runtime application role.
CREATE TABLE rain_event.event_snapshot (
  namespace      BYTEA        NOT NULL CHECK (octet_length(namespace) = 32),
  family         VARCHAR(128) NOT NULL CHECK (family ~ '^[a-z][a-z0-9_.-]{0,127}$'),
  stream_key     TEXT         NOT NULL CHECK (octet_length(stream_key) BETWEEN 1 AND 512),
  version        BIGINT       NOT NULL CHECK (version > 0),
  fingerprint    BYTEA        NOT NULL CHECK (octet_length(fingerprint) = 32),
  codec_type     VARCHAR(128) NOT NULL CHECK (codec_type ~ '^[a-z][a-z0-9_.-]{0,127}$'),
  codec_revision INTEGER      NOT NULL CHECK (codec_revision > 0),
  payload        BYTEA        NOT NULL CHECK (octet_length(payload) <= 1048576),
  checksum       BYTEA        NOT NULL CHECK (octet_length(checksum) = 32),
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT statement_timestamp(),
  PRIMARY KEY (namespace, family, stream_key, version),
  FOREIGN KEY (namespace, family, stream_key, version)
    REFERENCES rain_event.event_record (namespace, family, stream_key, version)
);

CREATE INDEX event_snapshot_latest_idx
  ON rain_event.event_snapshot (namespace, family, stream_key, version DESC)
  INCLUDE (fingerprint, codec_type, codec_revision, payload, checksum, created_at);
