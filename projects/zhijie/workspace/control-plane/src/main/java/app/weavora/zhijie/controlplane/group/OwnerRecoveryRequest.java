package app.weavora.zhijie.controlplane.group;

import java.time.Instant;

public record OwnerRecoveryRequest(
        String id, String groupId, String requesterId, String evidenceRef, GovernanceStatus status,
        String reviewerId, String decisionReason, Instant createdAt
) {
}
