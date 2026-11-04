package app.weavora.zhijie.controlplane.group;

import java.time.Instant;

public record OwnerTransfer(
        String id, String groupId, String fromUserId, String toUserId, GovernanceStatus status,
        Instant createdAt, Instant completedAt
) {
}
