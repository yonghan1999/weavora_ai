package app.weavora.zhijie.controlplane.grant;

import java.time.Instant;

public interface VoiceGateway {
    boolean isAvailable();
    String issueToken(String activityId, String userId, long memberGeneration, Instant expiresAt);
}
