-- A receipt claim is deliberately incomplete until the same event transaction records the exact
-- append result. Manual partial rows must fail closed instead of looking replayable.
ALTER TABLE rain_event.event_receipt
  ADD CONSTRAINT ck_event_receipt_completion_shape
    CHECK (
      (
        complete = false
        AND append_fingerprint IS NULL
        AND family IS NULL
        AND stream_key IS NULL
        AND response_type IS NULL
        AND response_revision IS NULL
        AND response_payload IS NULL
      )
      OR (
        complete = true
        AND append_fingerprint IS NOT NULL
        AND family IS NOT NULL
        AND stream_key IS NOT NULL
      )
    ),
  ADD CONSTRAINT ck_event_receipt_response_shape
    CHECK (
      (response_type IS NULL AND response_revision IS NULL AND response_payload IS NULL)
      OR (
        response_type ~ '^[a-z][a-z0-9_.-]{0,127}$'
        AND response_revision > 0
        AND response_payload IS NOT NULL
      )
    );
