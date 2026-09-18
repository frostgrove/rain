/* Bounded recurring projection-pass recovery: only live work states participate. */
CREATE INDEX projection_generation_runnable_idx
    ON rain_event.projection_generation (projection_name, generation)
    WHERE state IN ('building', 'active');
