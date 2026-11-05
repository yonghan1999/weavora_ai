package app.weavora.zhijie.controlplane.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

@Service
public class ObservationService {
    private final MeterRegistry registry;
    private final AtomicInteger pendingOutbox = new AtomicInteger();

    public ObservationService(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("zhijie.outbox.pending", pendingOutbox);
    }

    public void recordApiResult(String outcome) {
        registry.counter("zhijie.api.results", "outcome", outcome).increment();
    }

    public void recordRevocation(String channel, String outcome) {
        registry.counter("zhijie.revocation.results", "channel", channel, "outcome", outcome).increment();
    }

    public void setPendingOutbox(int count) {
        pendingOutbox.set(count);
    }

    public void recordOutboxQueued() {
        pendingOutbox.incrementAndGet();
    }

    public void recordOutboxDelivered() {
        pendingOutbox.updateAndGet(value -> Math.max(0, value - 1));
    }

    public Map<String, Object> summary() {
        return Map.of("pendingOutbox", pendingOutbox.get(), "status", "available");
    }
}
