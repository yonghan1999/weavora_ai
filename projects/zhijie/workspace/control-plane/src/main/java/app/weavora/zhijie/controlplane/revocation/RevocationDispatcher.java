package app.weavora.zhijie.controlplane.revocation;

import app.weavora.zhijie.controlplane.sync.OutboxEvent;
import app.weavora.zhijie.controlplane.sync.OutboxRepository;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RevocationDispatcher {
    private final OutboxRepository repository;
    private final List<RevocationTarget> targets;

    public RevocationDispatcher(OutboxRepository repository, List<RevocationTarget> targets) {
        this.repository = repository;
        this.targets = targets;
    }

    @Transactional
    @Scheduled(fixedDelay = 1000)
    public DispatchResult dispatchPending() {
        int delivered = 0;
        int failed = 0;
        for (OutboxEvent event : repository.listUndelivered(100)) {
            try {
                List<RevocationTarget> applicable = targets.stream().filter(target -> target.supports(event.type())).toList();
                for (RevocationTarget target : applicable) target.deliver(event);
                repository.markDelivered(event.eventId());
                delivered++;
            } catch (RuntimeException exception) {
                repository.recordFailure(event.eventId());
                failed++;
            }
        }
        return new DispatchResult(delivered, failed);
    }

    public record DispatchResult(int delivered, int failed) {
    }
}
