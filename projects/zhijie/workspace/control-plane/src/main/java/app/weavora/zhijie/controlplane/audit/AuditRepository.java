package app.weavora.zhijie.controlplane.audit;

import java.time.Instant;
import java.util.List;

public interface AuditRepository {
    void insert(AuditLog entry);
    List<AuditLog> list(String groupId, Instant before, int limit);
}
