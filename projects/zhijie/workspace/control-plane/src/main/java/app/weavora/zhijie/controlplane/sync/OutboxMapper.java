package app.weavora.zhijie.controlplane.sync;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OutboxMapper {
    void insert(OutboxEvent event);
    List<OutboxEvent> listAfter(@Param("cursor") long cursor, @Param("limit") int limit);
    List<OutboxEvent> listUndelivered(int limit);
    long latestSequence();
    void markDelivered(String eventId);
    void recordFailure(String eventId);
}
