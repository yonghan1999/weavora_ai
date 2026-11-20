package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyncRevocationTargetTest {
    @Test void eachRevocationClosesItsTargetBeforeBroadcasting() {
        for (String type : List.of("voice.revoked", "network.revoked", "lease.revoked")) {
            var sync = mock(SyncService.class);
            var event = event(type, "{\"user_id\":\"revoked-user\"}");

            new SyncRevocationTarget(sync, new ObjectMapper()).deliver(event);

            var order = inOrder(sync);
            order.verify(sync).closeUser("revoked-user");
            order.verify(sync).broadcast(event);
            verifyNoMoreInteractions(sync);
        }
    }

    @Test void ordinaryEventsStillBroadcastWithoutParsingARevocationPayload() {
        var sync = mock(SyncService.class);
        var event = event("message.created", "not-a-revocation-payload");

        new SyncRevocationTarget(sync, new ObjectMapper()).deliver(event);

        verify(sync).broadcast(event);
        verifyNoMoreInteractions(sync);
    }

    @Test void absentTargetDoesNotCloseAnotherUser() {
        for (String payload : List.of("{}", "{\"user_id\":null}")) {
            var sync = mock(SyncService.class);
            var event = event("network.revoked", payload);

            new SyncRevocationTarget(sync, new ObjectMapper()).deliver(event);

            verify(sync).broadcast(event);
            verifyNoMoreInteractions(sync);
        }
    }

    @Test void invalidRevocationPayloadFailsBeforeBroadcasting() {
        var sync = mock(SyncService.class);
        var target = new SyncRevocationTarget(sync, new ObjectMapper());

        assertThatThrownBy(() -> target.deliver(event("network.revoked", "invalid-json")))
                .isInstanceOf(IllegalStateException.class).hasMessage("Invalid revocation payload");

        verifyNoInteractions(sync);
    }

    @Test void broadcastFailureDoesNotPreventClosingTheRevokedUser() {
        var sync = mock(SyncService.class);
        var event = event("network.revoked", "{\"user_id\":\"revoked-user\"}");
        var failure = new IllegalStateException("delivery failed");
        doThrow(failure).when(sync).broadcast(event);

        assertThatThrownBy(() -> new SyncRevocationTarget(sync, new ObjectMapper()).deliver(event)).isSameAs(failure);

        var order = inOrder(sync);
        order.verify(sync).closeUser("revoked-user");
        order.verify(sync).broadcast(event);
        verifyNoMoreInteractions(sync);
    }

    private OutboxEvent event(String type, String payload) {
        return new OutboxEvent(1L, "event-1", "group", "activity", "revoked-user", type,
                payload, 1L, 1L, Instant.now(), null, 0);
    }
}
