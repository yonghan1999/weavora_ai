package app.weavora.zhijie.controlplane.message;

import java.time.Instant;

public record GroupMessage(
        String id, String groupId, String authorId, String body, Instant createdAt,
        Instant deletedAt, String deletedBy, DeleteKind deleteKind
) {
}
