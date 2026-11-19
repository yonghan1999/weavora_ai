CREATE INDEX idx_invitations_pending_recipient
    ON invitations (invitee_id, created_at DESC, id DESC) WHERE status = 'PENDING';
CREATE INDEX idx_invitations_pending_group
    ON invitations (group_id, created_at DESC, id DESC) WHERE status = 'PENDING';
