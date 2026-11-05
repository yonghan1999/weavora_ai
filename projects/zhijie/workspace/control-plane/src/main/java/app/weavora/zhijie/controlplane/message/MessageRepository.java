package app.weavora.zhijie.controlplane.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MessageRepository {
    void insert(GroupMessage message);
    Optional<GroupMessage> findById(String messageId);
    void update(GroupMessage message);
    List<GroupMessage> list(String groupId, Instant before, int limit);
}
