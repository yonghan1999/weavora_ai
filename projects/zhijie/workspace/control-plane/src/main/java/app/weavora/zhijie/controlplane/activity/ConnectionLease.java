package app.weavora.zhijie.controlplane.activity;

import java.time.Instant;

public record ConnectionLease(
        String id, String activityId, String userId, String deviceId, Long activityGeneration,
        Long memberGeneration, Long policyRevision, ChannelState voiceState, ChannelState networkState,
        Instant expiresAt, Instant revokedAt
) {
}
