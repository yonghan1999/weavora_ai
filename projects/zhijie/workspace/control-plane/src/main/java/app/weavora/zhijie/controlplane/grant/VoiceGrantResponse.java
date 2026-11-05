package app.weavora.zhijie.controlplane.grant;

import java.time.Instant;

public record VoiceGrantResponse(
        String grantId, String activityId, Long memberGeneration, String leaseId, String sfuUrl, String voiceToken, Instant expiresAt
) {
}
