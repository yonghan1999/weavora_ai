package app.weavora.zhijie.controlplane.social;

import java.time.Instant;

public record FriendRequest(
        String id,
        String requesterId,
        String addresseeId,
        FriendRequestStatus status,
        Instant createdAt,
        Instant respondedAt
) {
}
