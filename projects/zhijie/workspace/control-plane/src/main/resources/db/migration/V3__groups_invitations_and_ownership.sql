CREATE TABLE groups (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    avatar TEXT,
    description TEXT NOT NULL DEFAULT '',
    owner_user_id TEXT NOT NULL REFERENCES users(id),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'DISSOLVED')),
    created_at TIMESTAMPTZ NOT NULL,
    dissolved_at TIMESTAMPTZ
);

CREATE TABLE group_members (
    group_id TEXT NOT NULL REFERENCES groups(id),
    user_id TEXT NOT NULL REFERENCES users(id),
    role TEXT NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'LEFT', 'REMOVED')),
    joined_at TIMESTAMPTZ NOT NULL,
    left_at TIMESTAMPTZ,
    PRIMARY KEY (group_id, user_id)
);

CREATE UNIQUE INDEX uq_group_current_owner
    ON group_members (group_id) WHERE status = 'ACTIVE' AND role = 'OWNER';

CREATE TABLE invitations (
    id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES groups(id),
    inviter_id TEXT NOT NULL REFERENCES users(id),
    invitee_id TEXT NOT NULL REFERENCES users(id),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'INVALIDATED')),
    created_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_invitations_pending_invitee
    ON invitations (group_id, invitee_id) WHERE status = 'PENDING';

CREATE TABLE owner_transfers (
    id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES groups(id),
    from_user_id TEXT NOT NULL REFERENCES users(id),
    to_user_id TEXT NOT NULL REFERENCES users(id),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'CANCELLED', 'REJECTED', 'RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_owner_transfer_pending ON owner_transfers (group_id) WHERE status = 'PENDING';

CREATE TABLE owner_recovery_requests (
    id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES groups(id),
    requester_id TEXT NOT NULL REFERENCES users(id),
    evidence_ref TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'CANCELLED', 'REJECTED', 'RESOLVED')),
    reviewer_id TEXT,
    decision_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL
);
