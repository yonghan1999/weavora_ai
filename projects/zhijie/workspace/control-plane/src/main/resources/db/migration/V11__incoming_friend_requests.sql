CREATE INDEX idx_friend_requests_incoming
    ON friend_requests (addressee_id, created_at DESC, id DESC)
    WHERE status = 'PENDING';
