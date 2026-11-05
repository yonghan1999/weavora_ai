package app.weavora.zhijie.controlplane.sync;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OutboxService {
    private final OutboxRepository repository;
    private final Clock clock;
    private final app.weavora.zhijie.controlplane.observability.ObservationService observations;

    public OutboxService(OutboxRepository repository, Clock clock,
                         app.weavora.zhijie.controlplane.observability.ObservationService observations) {
        this.repository = repository;
        this.clock = clock;
        this.observations = observations;
    }

    public OutboxEvent record(String groupId, String activityId, String aggregateId, String type, String payload,
                              Long generation, Long policyRevision) {
        OutboxEvent event = new OutboxEvent(null, UUID.randomUUID().toString(), groupId, activityId, aggregateId,
                type, payload, generation, policyRevision, clock.instant(), null, 0);
        repository.insert(event);
        observations.recordOutboxQueued();
        return event;
    }
}
