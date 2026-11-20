package app.weavora.zhijie.controlplane.revocation;

import app.weavora.zhijie.controlplane.sync.OutboxEvent;
import app.weavora.zhijie.controlplane.sync.OutboxRepository;
import app.weavora.zhijie.controlplane.sync.ExternalDeliveryChannel;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalRevocationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ExternalRevocationDispatcher.class);
    private final OutboxRepository repository;
    private final List<RevocationTarget> targets;
    private final OutboxDeliveryStatus status;
    private final Clock clock;

    public ExternalRevocationDispatcher(OutboxRepository repository, List<RevocationTarget> targets,
                                        OutboxDeliveryStatus status, Clock clock) {
        this.repository = repository;
        this.targets = targets;
        this.status = status;
        this.clock = clock;
    }

    @Transactional
    public RevocationDispatcher.DispatchResult dispatchPending(ExternalDeliveryChannel channel) {
        Objects.requireNonNull(channel);
        int delivered = 0;
        int failed = 0;
        for (OutboxEvent event : repository.listDueExternal(100, clock.instant(), channel)) {
            try {
                for (RevocationTarget target : targets) if (target.supports(event.type())) target.deliver(event);
                status.delivered(event.eventId());
                delivered++;
            } catch (RuntimeException exception) {
                repository.recordExternalFailure(event.eventId(), clock.instant());
                status.failed("external");
                failed++;
            }
        }
        if (failed > 0) log.warn("External revocation delivery pending: failed={}", failed);
        return new RevocationDispatcher.DispatchResult(delivered, failed);
    }
}
