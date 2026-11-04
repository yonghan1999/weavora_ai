CREATE TABLE activity_members (
    activity_id TEXT NOT NULL REFERENCES activities(id),
    user_id TEXT NOT NULL REFERENCES users(id),
    device_id TEXT NOT NULL,
    member_generation BIGINT NOT NULL CHECK (member_generation > 0),
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'GRACE', 'REVOKED', 'LEFT')),
    joined_at TIMESTAMPTZ NOT NULL,
    grace_expires_at TIMESTAMPTZ,
    PRIMARY KEY (activity_id, user_id)
);

CREATE INDEX idx_activity_members_occupancy ON activity_members (activity_id, state);

CREATE TABLE account_activity (
    user_id TEXT PRIMARY KEY REFERENCES users(id),
    activity_id TEXT NOT NULL REFERENCES activities(id),
    device_id TEXT NOT NULL,
    member_generation BIGINT NOT NULL,
    operation_id TEXT NOT NULL
);

CREATE TABLE connection_leases (
    id TEXT PRIMARY KEY,
    activity_id TEXT NOT NULL REFERENCES activities(id),
    user_id TEXT NOT NULL REFERENCES users(id),
    device_id TEXT NOT NULL,
    activity_generation BIGINT NOT NULL,
    member_generation BIGINT NOT NULL,
    policy_revision BIGINT NOT NULL,
    voice_state TEXT NOT NULL CHECK (voice_state IN ('CONNECTING', 'CONNECTED', 'FAILED', 'DISCONNECTED', 'REVOKED')),
    network_state TEXT NOT NULL CHECK (network_state IN ('CONNECTING', 'CONNECTED', 'FAILED', 'DISCONNECTED', 'REVOKED')),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_connection_leases_current ON connection_leases (activity_id, user_id, revoked_at);

CREATE TABLE ip_allocations (
    activity_id TEXT NOT NULL REFERENCES activities(id),
    user_id TEXT NOT NULL REFERENCES users(id),
    ipv4 TEXT NOT NULL,
    allocated_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    PRIMARY KEY (activity_id, user_id)
);

CREATE UNIQUE INDEX uq_ip_allocations_active ON ip_allocations (activity_id, ipv4) WHERE released_at IS NULL;

CREATE TABLE ip_history (
    room_id TEXT NOT NULL REFERENCES rooms(id),
    user_id TEXT NOT NULL REFERENCES users(id),
    last_ipv4 TEXT NOT NULL,
    last_activity_id TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (room_id, user_id)
);
