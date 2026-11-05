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
}
