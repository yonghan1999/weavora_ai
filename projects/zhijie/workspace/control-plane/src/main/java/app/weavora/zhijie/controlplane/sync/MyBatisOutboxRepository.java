package app.weavora.zhijie.controlplane.sync;

import java.util.List;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisOutboxRepository implements OutboxRepository {
    private final OutboxMapper mapper;
    private final SyncSequenceAllocator sequences;
    public MyBatisOutboxRepository(OutboxMapper mapper, SyncSequenceAllocator sequences) {
        this.mapper = mapper; this.sequences = sequences;
    }
    public void insert(OutboxEvent event) { mapper.insert(event); }
    public List<OutboxEvent> listAfter(long cursor, int limit) {
        sequences.assignCommitted();
        return mapper.listAfter(cursor, limit);
    }
    public List<OutboxEvent> listPendingExternal(int limit) {
        sequences.assignCommitted();
        return mapper.listPendingExternal(limit);
    }
    public List<OutboxEvent> listUnsynchronized(int limit) {
        sequences.assignCommitted();
        return mapper.listUnsynchronized(limit);
    }
    public List<OutboxEvent> listDueExternal(int limit, Instant now) {
        sequences.assignCommitted();
        return mapper.listDueExternal(limit, now);
    }
    public long latestSequence() {
        sequences.assignCommitted();
        return mapper.latestSequence();
    }
    public void markDelivered(String eventId) { mapper.markDelivered(eventId); }
    public void markSynchronized(String eventId) { mapper.markSynchronized(eventId); }
    public void recordFailure(String eventId) { mapper.recordFailure(eventId); }
    public void recordExternalFailure(String eventId, Instant failedAt) { mapper.recordExternalFailure(eventId, failedAt); }
}
