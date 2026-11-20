package app.weavora.zhijie.controlplane.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.weavora.zhijie.controlplane.sync.OutboxEvent;
import app.weavora.zhijie.controlplane.sync.OutboxRepository;
import app.weavora.zhijie.controlplane.sync.SyncRevocationTarget;
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
        when(repository.listUnsynchronized(100)).thenReturn(List.of(event), List.of());
        when(repository.listPendingExternal(100)).thenReturn(List.of(event));
        SyncRevocationTarget sync = mock(SyncRevocationTarget.class);
        AtomicInteger attempts = new AtomicInteger();
        RevocationTarget target = new RevocationTarget() {
            public boolean supports(String eventType) { return true; }
            public void deliver(OutboxEvent ignored) {
                if (attempts.getAndIncrement() == 0) throw new IllegalStateException("temporary");
            }
        };
        RevocationDispatcher dispatcher = new RevocationDispatcher(repository, List.of(target),
                new app.weavora.zhijie.controlplane.observability.ObservationService(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                sync);

        assertThat(dispatcher.dispatchPending().failed()).isEqualTo(1);
        verify(repository).recordFailure("event");
        verify(repository, never()).markDelivered("event");
        assertThat(dispatcher.dispatchPending().delivered()).isEqualTo(1);
        verify(repository).markDelivered("event");
        verify(repository).markSynchronized("event");
        verify(sync).deliver(event);
        var order = inOrder(sync, repository);
        order.verify(sync).deliver(event);
        order.verify(repository).markSynchronized("event");
        order.verify(repository).recordFailure("event");
    }

    @Test
    void failedSyncDoesNotAdvancePastItsSequenceOrAcknowledgeDelivery() {
        var first = new OutboxEvent(1L, "first", null, null, "user", "test.changed",
                "{}", null, null, Instant.now(), null, 0);
        var later = new OutboxEvent(2L, "later", null, null, "user", "test.changed",
                "{}", null, null, Instant.now(), null, 0);
        var repository = mock(OutboxRepository.class);
        when(repository.listUnsynchronized(100)).thenReturn(List.of(first, later));
        var sync = mock(SyncRevocationTarget.class);
        doThrow(new IllegalStateException("temporary")).when(sync).deliver(first);
        var dispatcher = new RevocationDispatcher(repository, List.of(),
                new app.weavora.zhijie.controlplane.observability.ObservationService(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), sync);

        assertThat(dispatcher.dispatchPending()).isEqualTo(new RevocationDispatcher.DispatchResult(0, 1));
        verify(repository).recordFailure("first");
        verify(sync, never()).deliver(later);
        verify(repository, never()).markSynchronized(anyString());
        verify(repository, never()).markDelivered(anyString());
    }

    @Test
    void eventsWithoutExternalTargetsCompleteInSyncSequenceOrder() {
        var first = new OutboxEvent(3L, "first", null, null, "user", "test.changed",
                "{}", null, null, Instant.now(), null, 0);
        var later = new OutboxEvent(9L, "later", null, null, "user", "test.changed",
                "{}", null, null, Instant.now(), null, 0);
        var repository = mock(OutboxRepository.class);
        when(repository.listUnsynchronized(100)).thenReturn(List.of(first, later));
        var sync = mock(SyncRevocationTarget.class);
        var dispatcher = new RevocationDispatcher(repository, List.of(),
                new app.weavora.zhijie.controlplane.observability.ObservationService(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), sync);

        assertThat(dispatcher.dispatchPending()).isEqualTo(new RevocationDispatcher.DispatchResult(2, 0));
        var order = inOrder(sync, repository);
        order.verify(sync).deliver(first);
        order.verify(repository).markSynchronized("first");
        order.verify(repository).markDelivered("first");
        order.verify(sync).deliver(later);
        order.verify(repository).markSynchronized("later");
        order.verify(repository).markDelivered("later");
        verify(repository, never()).recordFailure(anyString());
    }
}
