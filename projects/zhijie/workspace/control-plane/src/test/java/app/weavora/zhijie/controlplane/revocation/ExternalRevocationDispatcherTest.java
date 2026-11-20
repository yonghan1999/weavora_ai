package app.weavora.zhijie.controlplane.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import app.weavora.zhijie.controlplane.observability.ObservationService;
import app.weavora.zhijie.controlplane.sync.OutboxEvent;
import app.weavora.zhijie.controlplane.sync.OutboxRepository;
import app.weavora.zhijie.controlplane.sync.ExternalDeliveryChannel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ExternalRevocationDispatcherTest {
    @Test void failedBatchRemainsPendingReportsOnlyCountAndContinuesToLaterTarget(CapturedOutput output) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var failed = new OutboxEvent(1L, "private-event", "private-group", null, "private-user", "test.revoked",
                "private-payload", null, null, now, null, 0);
        var ready = new OutboxEvent(2L, "ready", null, null, "user", "test.ready",
                "{}", null, null, now, null, 0);
        var repository = mock(OutboxRepository.class);
        when(repository.listDueExternal(100, now, ExternalDeliveryChannel.OTHER)).thenReturn(List.of(failed, ready));
        var target = mock(RevocationTarget.class);
        when(target.supports(anyString())).thenReturn(true);
        doThrow(new IllegalStateException("private-url-and-token")).when(target).deliver(failed);
        var meters = new SimpleMeterRegistry();
        var observations = new ObservationService(meters);
        observations.recordOutboxQueued();
        observations.recordOutboxQueued();
        var dispatcher = new ExternalRevocationDispatcher(repository, List.of(target),
                new OutboxDeliveryStatus(repository, observations), Clock.fixed(now, ZoneOffset.UTC));

        assertThat(dispatcher.dispatchPending(ExternalDeliveryChannel.OTHER)).isEqualTo(new RevocationDispatcher.DispatchResult(1, 1));
        verify(repository).recordExternalFailure("private-event", now);
        verify(repository, never()).markDelivered("private-event");
        verify(target).deliver(ready);
        verify(repository).markDelivered("ready");
        assertThat(observations.summary()).containsEntry("pendingOutbox", 1);
        assertThat(meters.get("zhijie.revocation.results").tags("channel", "external", "outcome", "failed").counter().count()).isEqualTo(1);
        assertThat(output.getAll()).contains("External revocation delivery pending: failed=1")
                .doesNotContain("private-", "Authorization", "access_token", "IllegalStateException");
    }
}
