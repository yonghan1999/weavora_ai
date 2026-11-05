package app.weavora.zhijie.controlplane.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.weavora.zhijie.controlplane.sync.OutboxEvent;
import app.weavora.zhijie.controlplane.sync.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RevocationDispatcherTest {
    @Test
    void failedDeliveryIsCountedAndCanBeRetriedSuccessfully() {
        OutboxEvent event = new OutboxEvent(1L, "event", "group", "activity", "user", "voice.revoked",
                "{}", 1L, 1L, Instant.now(), null, 0);
        OutboxRepository repository = mock(OutboxRepository.class);
        when(repository.listUndelivered(100)).thenReturn(List.of(event));
        AtomicInteger attempts = new AtomicInteger();
        RevocationTarget target = new RevocationTarget() {
            public boolean supports(String eventType) { return true; }
            public void deliver(OutboxEvent ignored) {
                if (attempts.getAndIncrement() == 0) throw new IllegalStateException("temporary");
            }
        };
        RevocationDispatcher dispatcher = new RevocationDispatcher(repository, List.of(target),
                new app.weavora.zhijie.controlplane.observability.ObservationService(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        assertThat(dispatcher.dispatchPending().failed()).isEqualTo(1);
        verify(repository).recordFailure("event");
        assertThat(dispatcher.dispatchPending().delivered()).isEqualTo(1);
        verify(repository).markDelivered("event");
    }
}
