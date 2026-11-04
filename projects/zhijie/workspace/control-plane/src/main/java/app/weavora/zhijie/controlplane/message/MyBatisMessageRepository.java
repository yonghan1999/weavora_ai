package app.weavora.zhijie.controlplane.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisMessageRepository implements MessageRepository {
    private final MessageMapper mapper;

    public MyBatisMessageRepository(MessageMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(GroupMessage message) { mapper.insert(message); }
    public Optional<GroupMessage> findById(String messageId) { return mapper.findById(messageId); }
    public void update(GroupMessage message) { mapper.update(message); }
    public List<GroupMessage> list(String groupId, Instant before, int limit) { return mapper.list(groupId, before, limit); }
}
