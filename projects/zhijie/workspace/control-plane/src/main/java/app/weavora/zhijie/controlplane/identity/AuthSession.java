package app.weavora.zhijie.controlplane.identity;

import java.time.Instant;

public record AuthSession(
        String id,
        String userId,
        String deviceId,
        String refreshTokenHash,
        String accessTokenHash,
        Instant expiresAt,
        Instant accessExpiresAt,
        Instant revokedAt,
        Long generation
) {
    public boolean allowsActivity(String user, String device, Instant now) {
        return revokedAt == null && userId.equals(user) && deviceId.equals(device) && now.isBefore(expiresAt);
    }
}
