package app.weavora.zhijie.controlplane.audit;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.group.GroupRole;
import app.weavora.zhijie.controlplane.group.GroupService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditRepository repository;
    private final GroupService groups;
    private final AuditRecorder recorder;

    public AuditService(AuditRepository repository, GroupService groups, AuditRecorder recorder) {
        this.repository = repository;
        this.groups = groups;
        this.recorder = recorder;
    }

    public void record(String groupId, String actorId, String targetType, String targetId,
                       String action, String result, String correlationId) {
        recorder.record(groupId, actorId, targetType, targetId, action, result, correlationId);
    }

    public List<AuditLog> list(String actorId, String groupId, Instant before, int limit) {
        return list(actorId, groupId, before, null, limit);
    }

    public List<AuditLog> list(String actorId, String groupId, Instant before, String beforeId, int limit) {
        if (groups.requireMembership(groupId, actorId).role() == GroupRole.MEMBER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "仅当前群主和管理员可查看管理记录", false);
        }
        if (beforeId != null && (beforeId.isBlank() || beforeId.length() > 128)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AUDIT_CURSOR", "管理记录游标无效", false);
        }
        return repository.list(groupId, before, beforeId, Math.min(Math.max(limit, 1), 100));
    }
}
