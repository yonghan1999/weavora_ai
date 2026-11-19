ALTER TABLE owner_recovery_requests
    ADD COLUMN expected_owner_user_id TEXT REFERENCES users(id),
    ADD COLUMN target_user_id TEXT REFERENCES users(id),
    ADD COLUMN decided_at TIMESTAMPTZ;

ALTER TABLE owner_recovery_requests ALTER COLUMN status SET DEFAULT 'PENDING';

-- 旧申请缺少提交时归属快照，只允许人工驳回；不得推断为当前群主。
CREATE INDEX idx_owner_recovery_group ON owner_recovery_requests(group_id, created_at DESC, id DESC);
