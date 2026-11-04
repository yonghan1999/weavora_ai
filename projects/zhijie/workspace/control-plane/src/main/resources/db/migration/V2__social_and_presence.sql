CREATE TABLE friend_requests (
    id TEXT PRIMARY KEY,
    requester_id TEXT NOT NULL REFERENCES users(id),
    addressee_id TEXT NOT NULL REFERENCES users(id),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL,
    responded_at TIMESTAMPTZ,
    CHECK (requester_id <> addressee_id)
);

CREATE UNIQUE INDEX uq_friend_requests_pending_pair
    ON friend_requests (LEAST(requester_id, addressee_id), GREATEST(requester_id, addressee_id))
    WHERE status = 'PENDING';

CREATE TABLE friendships (
    id TEXT PRIMARY KEY,
    user_low_id TEXT NOT NULL REFERENCES users(id),
    user_high_id TEXT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_low_id, user_high_id),
    CHECK (user_low_id < user_high_id)
);

CREATE INDEX idx_friendships_low ON friendships (user_low_id);
CREATE INDEX idx_friendships_high ON friendships (user_high_id);

CREATE TABLE presence (
    user_id TEXT PRIMARY KEY REFERENCES users(id),
    last_seen_at TIMESTAMPTZ NOT NULL
);
