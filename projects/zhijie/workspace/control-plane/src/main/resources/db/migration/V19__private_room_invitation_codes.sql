ALTER TABLE invitations ALTER COLUMN invitee_id DROP NOT NULL;
ALTER TABLE invitations ADD COLUMN target_room_id TEXT REFERENCES rooms(id),
    ADD COLUMN invitee_email TEXT, ADD COLUMN code_hash TEXT UNIQUE,
    ADD COLUMN accepted_member_since TIMESTAMPTZ;
ALTER TABLE invitations ADD CONSTRAINT invitation_recipient_shape CHECK (
    (target_room_id IS NULL AND invitee_id IS NOT NULL AND invitee_email IS NULL AND code_hash IS NULL)
    OR (target_room_id IS NOT NULL AND invitee_email IS NOT NULL AND code_hash IS NOT NULL
        AND (status <> 'ACCEPTED' OR (invitee_id IS NOT NULL AND accepted_member_since IS NOT NULL)))
);
