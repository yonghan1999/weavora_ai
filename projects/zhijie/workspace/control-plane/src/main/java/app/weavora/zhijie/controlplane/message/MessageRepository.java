package app.weavora.zhijie.controlplane.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MessageRepository {
    void insert(GroupMessage message);
    Optional<GroupMessage> findById(String messageId);
    boolean markDeleted(GroupMessage message);
    List<GroupMessage> list(String groupId, Instant before, String beforeId, int limit);
}
