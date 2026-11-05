CREATE TABLE network_policy_revisions (
    revision BIGINT PRIMARY KEY,
    mode TEXT NOT NULL CHECK (mode IN ('DIRECT_ONLY', 'DIRECT_WITH_RELAY_FALLBACK', 'RELAY_ONLY')),
    existing_connection_action TEXT NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    actor_ref TEXT NOT NULL
);

INSERT INTO network_policy_revisions (revision, mode, existing_connection_action, effective_at, actor_ref)
VALUES (1, 'DIRECT_WITH_RELAY_FALLBACK', 'MIGRATE_WHEN_REQUIRED', NOW(), 'architecture-baseline');

ALTER TABLE activity_grants ADD COLUMN allowed_paths TEXT;
UPDATE activity_grants SET allowed_paths = 'DIRECT,RELAY' WHERE kind = 'NETWORK' AND allowed_paths IS NULL;
