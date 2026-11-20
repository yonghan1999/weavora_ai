ALTER TABLE account_activity ADD COLUMN auth_session_id TEXT REFERENCES auth_sessions(id);
CREATE INDEX idx_account_activity_auth_session ON account_activity(auth_session_id);
