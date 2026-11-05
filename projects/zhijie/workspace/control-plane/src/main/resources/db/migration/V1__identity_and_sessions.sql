CREATE TABLE users (
    id TEXT PRIMARY KEY,
    email_normalized TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK (status IN ('UNVERIFIED', 'ACTIVE', 'DISABLED')),
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE email_challenges (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id),
    purpose TEXT NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'PASSWORD_RESET')),
    code_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts >= 0),
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_email_challenges_latest
    ON email_challenges (user_id, purpose, created_at DESC);

CREATE TABLE auth_sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id),
    device_id TEXT NOT NULL,
    refresh_token_hash TEXT NOT NULL UNIQUE,
    access_token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    access_expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    generation BIGINT NOT NULL CHECK (generation > 0)
);

CREATE INDEX idx_auth_sessions_user ON auth_sessions (user_id);
