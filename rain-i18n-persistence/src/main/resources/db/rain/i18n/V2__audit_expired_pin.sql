ALTER TABLE rain_i18n.i18n_audit
    DROP CONSTRAINT i18n_audit_kind_check;

ALTER TABLE rain_i18n.i18n_audit
    ADD CONSTRAINT i18n_audit_kind_check
        CHECK (kind IN ('PUBLISHED', 'ACTIVATED', 'ROLLED_BACK', 'PRUNED', 'PINNED', 'PIN_RELEASED', 'PIN_EXPIRED'));
