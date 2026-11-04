package app.weavora.zhijie.controlplane.audit;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.group.GroupRole;
import app.weavora.zhijie.controlplane.group.GroupService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditRepository repository;
    private final GroupService groups;
    private final Clock clock;

    public AuditService(AuditRepository repository, GroupService groups, Clock clock) {
        this.repository = repository;
        this.groups = groups;
        this.clock = clock;
    }

    public void record(String groupId, String actorId, String targetType, String targetId,
                       String action, String result, String correlationId) {
        AuditLog entry = new AuditLog(UUID.randomUUID().toString(), groupId, actorId, targetType, targetId,
                action, result, clock.instant(), correlationId);
        repository.insert(entry);
    }

    public List<AuditLog> list(String actorId, String groupId, Instant before, int limit) {
        if (groups.requireMembership(groupId, actorId).role() == GroupRole.MEMBER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "仅当前群主和管理员可查看管理记录", false);
        }
        return repository.list(groupId, before, Math.min(Math.max(limit, 1), 100));
    }
}
