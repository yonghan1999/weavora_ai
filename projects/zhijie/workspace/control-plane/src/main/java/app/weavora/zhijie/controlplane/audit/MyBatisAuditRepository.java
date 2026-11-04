package app.weavora.zhijie.controlplane.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisAuditRepository implements AuditRepository {
    private final AuditMapper mapper;

    public MyBatisAuditRepository(AuditMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(AuditLog entry) { mapper.insert(entry); }
    public List<AuditLog> list(String groupId, Instant before, int limit) { return mapper.list(groupId, before, limit); }
}
