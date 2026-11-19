CREATE TABLE network_peer_endpoints (
    activity_id TEXT NOT NULL REFERENCES activities(id),
    user_id TEXT NOT NULL REFERENCES users(id),
    grant_id TEXT NOT NULL REFERENCES activity_grants(id),
    member_generation BIGINT NOT NULL,
    endpoint TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (activity_id, user_id)
);
