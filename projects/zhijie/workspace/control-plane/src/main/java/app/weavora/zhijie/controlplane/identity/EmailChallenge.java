package app.weavora.zhijie.controlplane.identity;

import java.time.Instant;

public record EmailChallenge(
        String id,
        String userId,
        ChallengePurpose purpose,
        String codeHash,
        Instant expiresAt,
        Integer attempts,
        Instant consumedAt,
        Instant createdAt
) {
}
