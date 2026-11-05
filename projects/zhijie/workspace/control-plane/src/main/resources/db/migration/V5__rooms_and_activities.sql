CREATE TABLE rooms (
    id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES groups(id),
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    game_name TEXT NOT NULL DEFAULT '',
    password_hash TEXT,
    owner_user_id TEXT NOT NULL REFERENCES users(id),
    open_state TEXT NOT NULL CHECK (open_state IN ('OPEN', 'PAUSED')),
    lifecycle TEXT NOT NULL CHECK (lifecycle IN ('AVAILABLE', 'DELETED')),
    current_activity_id TEXT
);

CREATE INDEX idx_rooms_group ON rooms (group_id);

CREATE TABLE activities (
    id TEXT PRIMARY KEY,
    room_id TEXT NOT NULL REFERENCES rooms(id),
    activity_generation BIGINT NOT NULL CHECK (activity_generation > 0),
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'ENDING', 'ENDED')),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_activities_current ON activities (room_id) WHERE state = 'ACTIVE';
