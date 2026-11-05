package app.weavora.zhijie.controlplane.sync;

import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisOutboxRepository implements OutboxRepository {
    private final OutboxMapper mapper;
    public MyBatisOutboxRepository(OutboxMapper mapper) { this.mapper = mapper; }
    public void insert(OutboxEvent event) { mapper.insert(event); }
    public List<OutboxEvent> listAfter(long cursor, int limit) { return mapper.listAfter(cursor, limit); }
    public List<OutboxEvent> listUndelivered(int limit) { return mapper.listUndelivered(limit); }
    public long latestSequence() { return mapper.latestSequence(); }
    public void markDelivered(String eventId) { mapper.markDelivered(eventId); }
    public void recordFailure(String eventId) { mapper.recordFailure(eventId); }
}
