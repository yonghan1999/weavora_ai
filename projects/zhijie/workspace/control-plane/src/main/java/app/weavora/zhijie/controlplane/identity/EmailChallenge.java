package app.weavora.zhijie.controlplane.identity;

import java.time.Instant;

public record EmailChallenge(
        String id,
        String userId,
        ChallengePurpose purpose,
        String codeHash,
        Instant expiresAt,
        int attempts,
        Instant consumedAt,
        Instant createdAt
) {
}
