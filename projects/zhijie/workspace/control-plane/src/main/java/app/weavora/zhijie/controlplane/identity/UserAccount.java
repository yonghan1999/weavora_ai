package app.weavora.zhijie.controlplane.identity;

import java.time.Instant;

public record UserAccount(
        String id,
        String emailNormalized,
        AccountStatus status,
        String passwordHash,
        Instant createdAt
) {
}
