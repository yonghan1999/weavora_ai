package app.weavora.zhijie.controlplane.group;

import java.time.Instant;

public record Group(
        String id, String name, String avatar, String description, String ownerUserId,
        GroupStatus status, Instant createdAt, Instant dissolvedAt
) {
}
