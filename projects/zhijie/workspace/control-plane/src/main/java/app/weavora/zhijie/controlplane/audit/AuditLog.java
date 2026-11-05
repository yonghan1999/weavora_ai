package app.weavora.zhijie.controlplane.audit;

import java.time.Instant;

public record AuditLog(
        String id, String groupId, String actorId, String targetType, String targetId,
        String action, String result, Instant occurredAt, String correlationId
) {
}
