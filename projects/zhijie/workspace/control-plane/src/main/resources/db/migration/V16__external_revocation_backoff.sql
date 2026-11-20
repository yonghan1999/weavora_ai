ALTER TABLE outbox_events
    ADD COLUMN external_attempts INTEGER NOT NULL DEFAULT 0 CHECK (external_attempts >= 0),
    ADD COLUMN external_next_attempt_at TIMESTAMPTZ;

CREATE INDEX idx_outbox_events_external_due ON outbox_events ((COALESCE(external_next_attempt_at, occurred_at)), sequence)
    WHERE delivered_at IS NULL AND sync_delivered_at IS NOT NULL;
