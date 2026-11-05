CREATE TABLE outbox_events (
    sequence BIGSERIAL PRIMARY KEY,
    event_id TEXT NOT NULL UNIQUE,
    group_id TEXT,
    activity_id TEXT,
    aggregate_id TEXT NOT NULL,
    type TEXT NOT NULL,
    payload TEXT NOT NULL,
    generation BIGINT,
    policy_revision BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL CHECK (attempts >= 0)
);

CREATE INDEX idx_outbox_events_pending ON outbox_events (sequence) WHERE delivered_at IS NULL;

CREATE TABLE activity_grants (
    id TEXT PRIMARY KEY,
    activity_id TEXT NOT NULL REFERENCES activities(id),
    user_id TEXT NOT NULL REFERENCES users(id),
    lease_id TEXT NOT NULL REFERENCES connection_leases(id),
    member_generation BIGINT NOT NULL,
    policy_revision BIGINT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('VOICE', 'NETWORK')),
    token_hash TEXT NOT NULL UNIQUE,
    public_key TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_activity_grants_current ON activity_grants (activity_id, user_id, kind, member_generation);
