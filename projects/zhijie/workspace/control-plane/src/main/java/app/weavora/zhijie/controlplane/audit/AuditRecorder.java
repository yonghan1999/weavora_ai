package app.weavora.zhijie.controlplane.audit;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 只负责追加记录，不依赖群权限服务；由业务事务保证操作与记录同时提交。 */
@Service
public class AuditRecorder {
    private final AuditRepository repository;
    private final Clock clock;

    public AuditRecorder(AuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void record(String groupId, String actorId, String targetType, String targetId,
                       String action, String result, String correlationId) {
        repository.insert(new AuditLog(UUID.randomUUID().toString(), groupId, actorId, targetType, targetId,
                action, result, clock.instant(), correlationId));
    }
}
