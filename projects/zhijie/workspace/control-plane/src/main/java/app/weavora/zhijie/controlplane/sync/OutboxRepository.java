package app.weavora.zhijie.controlplane.sync;

import java.util.List;

public interface OutboxRepository {
    void insert(OutboxEvent event);
    List<OutboxEvent> listAfter(long cursor, int limit);
    List<OutboxEvent> listPendingExternal(int limit);
    List<OutboxEvent> listUnsynchronized(int limit);
    long latestSequence();
    void markDelivered(String eventId);
    void markSynchronized(String eventId);
    void recordFailure(String eventId);
}
