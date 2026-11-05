package app.weavora.zhijie.controlplane.sync;

import java.time.Instant;

public record OutboxEvent(
        Long sequence, String eventId, String groupId, String activityId, String aggregateId, String type,
        String payload, Long generation, Long policyRevision, Instant occurredAt, Instant deliveredAt, Integer attempts
) {
}
