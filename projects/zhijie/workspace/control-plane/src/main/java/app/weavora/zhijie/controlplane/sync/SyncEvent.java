package app.weavora.zhijie.controlplane.sync;

import java.time.Instant;

public record SyncEvent(
        String eventId, String groupId, String activityId, Long memberGeneration, Long policyRevision,
        Long sequence, String type, Instant occurredAt, String payload
) {
    public static SyncEvent from(OutboxEvent event) {
        return new SyncEvent(event.eventId(), event.groupId(), event.activityId(), event.generation(),
                event.policyRevision(), event.sequence(), event.type(), event.occurredAt(), event.payload());
    }
}
