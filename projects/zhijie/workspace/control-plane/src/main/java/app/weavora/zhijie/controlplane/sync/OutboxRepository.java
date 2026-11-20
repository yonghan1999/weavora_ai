package app.weavora.zhijie.controlplane.sync;

import java.util.List;
import java.time.Instant;

public interface OutboxRepository {
    void insert(OutboxEvent event);
    List<OutboxEvent> listAfter(long cursor, int limit);
    List<OutboxEvent> listPendingExternal(int limit);
    List<OutboxEvent> listDueExternal(int limit, Instant now);
    List<OutboxEvent> listUnsynchronized(int limit);
    long latestSequence();
    void markDelivered(String eventId);
    void markSynchronized(String eventId);
    void recordFailure(String eventId);
    void recordExternalFailure(String eventId, Instant failedAt);
}
