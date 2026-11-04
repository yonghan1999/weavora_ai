package app.weavora.zhijie.controlplane.group;

import java.time.Instant;

public record GroupInvitation(
        String id, String groupId, String inviterId, String inviteeId, InvitationStatus status,
        Instant createdAt, Instant acceptedAt, Instant revokedAt, Instant invalidatedAt
) {
}
