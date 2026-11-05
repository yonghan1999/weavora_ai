package app.weavora.zhijie.controlplane.activity;

import java.time.Instant;

public record ActivityMember(
        String activityId, String userId, String deviceId, Long memberGeneration,
        MemberState state, Instant joinedAt, Instant graceExpiresAt
) {
}
