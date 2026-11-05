CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES groups(id),
    author_id TEXT NOT NULL REFERENCES users(id),
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    deleted_by TEXT REFERENCES users(id),
    delete_kind TEXT NOT NULL CHECK (delete_kind IN ('NONE', 'AUTHOR', 'MODERATOR')),
    CHECK ((deleted_at IS NULL AND delete_kind = 'NONE') OR (deleted_at IS NOT NULL AND delete_kind <> 'NONE'))
);

CREATE INDEX idx_messages_group_history ON messages (group_id, created_at DESC, id DESC);

CREATE TABLE audit_logs (
    id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES groups(id),
    actor_id TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    action TEXT NOT NULL,
    result TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id TEXT NOT NULL
);

CREATE INDEX idx_audit_logs_group_history ON audit_logs (group_id, occurred_at DESC, id DESC);
REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs FROM PUBLIC;
