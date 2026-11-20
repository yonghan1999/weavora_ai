package app.weavora.zhijie.controlplane.revocation;

import app.weavora.zhijie.controlplane.sync.OutboxEvent;
import app.weavora.zhijie.controlplane.sync.OutboxRepository;
import app.weavora.zhijie.controlplane.sync.SyncRevocationTarget;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncOutboxDispatcher {
    private final OutboxRepository repository;
    private final List<RevocationTarget> targets;
    private final SyncRevocationTarget sync;
    private final OutboxDeliveryStatus status;

    public SyncOutboxDispatcher(OutboxRepository repository, List<RevocationTarget> targets,
                                SyncRevocationTarget sync, OutboxDeliveryStatus status) {
        this.repository = repository;
        this.targets = targets;
        this.sync = sync;
        this.status = status;
    }

    @Transactional
    @Scheduled(fixedDelay = 1000)
    public RevocationDispatcher.DispatchResult dispatchPending() {
        int delivered = 0;
        int failed = 0;
        for (OutboxEvent event : repository.listUnsynchronized(100)) {
            try {
                sync.deliver(event);
                repository.markSynchronized(event.eventId());
                if (targets.stream().noneMatch(target -> target.supports(event.type()))) {
                    status.delivered(event.eventId());
                    delivered++;
                }
            } catch (RuntimeException exception) {
                repository.recordFailure(event.eventId());
                status.failed("sync");
                failed++;
                break;
            }
        }
        return new RevocationDispatcher.DispatchResult(delivered, failed);
    }
}
