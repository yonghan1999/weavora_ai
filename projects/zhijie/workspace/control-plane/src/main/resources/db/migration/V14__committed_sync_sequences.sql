-- 已有游标保持原值；迁移先等待在途写入，确保历史映射完整。
LOCK TABLE outbox_events IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE sync_event_sequences (
    sequence BIGSERIAL PRIMARY KEY,
    event_id TEXT NOT NULL UNIQUE REFERENCES outbox_events(event_id) ON DELETE CASCADE
);

INSERT INTO sync_event_sequences (sequence, event_id)
SELECT sequence, event_id FROM outbox_events ORDER BY sequence;

SELECT setval('sync_event_sequences_sequence_seq',
    COALESCE((SELECT MAX(sequence) FROM sync_event_sequences), 1),
    EXISTS (SELECT 1 FROM sync_event_sequences));
