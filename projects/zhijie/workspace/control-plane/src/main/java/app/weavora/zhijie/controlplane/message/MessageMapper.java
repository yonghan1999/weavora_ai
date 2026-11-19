package app.weavora.zhijie.controlplane.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MessageMapper {
    void insert(GroupMessage message);
    Optional<GroupMessage> findById(String messageId);
    boolean markDeleted(GroupMessage message);
    List<GroupMessage> list(@Param("groupId") String groupId, @Param("before") Instant before,
                            @Param("beforeId") String beforeId, @Param("limit") int limit);
}
