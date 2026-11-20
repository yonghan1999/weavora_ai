package app.weavora.zhijie.controlplane.revocation;

import app.weavora.zhijie.controlplane.observability.ObservationService;
import app.weavora.zhijie.controlplane.sync.OutboxRepository;
import org.springframework.stereotype.Service;

@Service
public class OutboxDeliveryStatus {
    private final OutboxRepository repository;
    private final ObservationService observations;

    public OutboxDeliveryStatus(OutboxRepository repository, ObservationService observations) {
        this.repository = repository;
        this.observations = observations;
    }

    public void delivered(String eventId) {
        repository.markDelivered(eventId);
        observations.recordOutboxDelivered();
        observations.recordRevocation("outbox", "delivered");
    }

    public void failed(String channel) { observations.recordRevocation(channel, "failed"); }
}
