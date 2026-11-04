package app.weavora.zhijie.controlplane.audit;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditMapper {
    void insert(AuditLog entry);
    List<AuditLog> list(@Param("groupId") String groupId, @Param("before") Instant before, @Param("limit") int limit);
}
