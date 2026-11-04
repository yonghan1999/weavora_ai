package app.weavora.zhijie.controlplane.identity;

import java.time.Instant;

public record AuthTokens(
        String sessionId,
        String accessToken,
        String refreshToken,
        Instant accessExpiresAt,
        Instant refreshExpiresAt
) {
}
