package app.weavora.zhijie.controlplane.revocation;

import app.weavora.zhijie.controlplane.sync.OutboxEvent;
import app.weavora.zhijie.controlplane.sync.OutboxRepository;
import app.weavora.zhijie.controlplane.sync.SyncRevocationTarget;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RevocationDispatcher {
    private final OutboxRepository repository;
    private final List<RevocationTarget> targets;
    private final SyncRevocationTarget sync;
    private final app.weavora.zhijie.controlplane.observability.ObservationService observations;

    public RevocationDispatcher(OutboxRepository repository, List<RevocationTarget> targets,
                                app.weavora.zhijie.controlplane.observability.ObservationService observations,
                                SyncRevocationTarget sync) {
        this.repository = repository;
        this.targets = targets;
        this.observations = observations;
        this.sync = sync;
    }

    @Transactional
    @Scheduled(fixedDelay = 1000)
    public DispatchResult dispatchPending() {
        int delivered = 0;
        int failed = 0;
        for (OutboxEvent event : repository.listUnsynchronized(100)) {
            try {
                sync.deliver(event);
                repository.markSynchronized(event.eventId());
                if (targets.stream().noneMatch(target -> target.supports(event.type()))) {
                    markDelivered(event);
                    delivered++;
                }
            } catch (RuntimeException exception) {
                repository.recordFailure(event.eventId());
                failed++;
                break;
            }
        }
        for (OutboxEvent event : repository.listPendingExternal(100)) {
            try {
                List<RevocationTarget> applicable = targets.stream().filter(target -> target.supports(event.type())).toList();
                for (RevocationTarget target : applicable) target.deliver(event);
                markDelivered(event);
                delivered++;
            } catch (RuntimeException exception) {
                repository.recordFailure(event.eventId());
                failed++;
            }
        }
        return new DispatchResult(delivered, failed);
    }

    private void markDelivered(OutboxEvent event) {
        repository.markDelivered(event.eventId());
        observations.recordOutboxDelivered();
        observations.recordRevocation("outbox", "delivered");
    }

    public record DispatchResult(int delivered, int failed) {
    }
}
