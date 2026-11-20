package app.weavora.zhijie.controlplane.sync;

import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OutboxMapper {
    void insert(OutboxEvent event);
    List<OutboxEvent> listAfter(@Param("cursor") long cursor, @Param("limit") int limit);
    List<OutboxEvent> listPendingExternal(int limit);
    List<OutboxEvent> listDueExternal(@Param("limit") int limit, @Param("now") Instant now,
                                     @Param("channel") ExternalDeliveryChannel channel);
    List<OutboxEvent> listUnsynchronized(int limit);
    long latestSequence();
    void markDelivered(String eventId);
    void markSynchronized(String eventId);
    void recordFailure(String eventId);
    void recordExternalFailure(@Param("eventId") String eventId, @Param("failedAt") Instant failedAt);
}
