ALTER TABLE outbox_events ADD COLUMN sync_delivered_at TIMESTAMPTZ;

UPDATE outbox_events SET sync_delivered_at = delivered_at WHERE delivered_at IS NOT NULL;

CREATE INDEX idx_outbox_events_sync_pending ON outbox_events (sequence) WHERE sync_delivered_at IS NULL;
