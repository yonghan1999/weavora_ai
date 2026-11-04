package app.weavora.zhijie.controlplane.grant;

import java.time.Instant;

public record ActivityGrant(
        String id, String activityId, String userId, String leaseId, Long memberGeneration, Long policyRevision,
        String kind, String tokenHash, String publicKey, String allowedPaths,
        Instant expiresAt, Instant revokedAt, Instant createdAt
) {
}
