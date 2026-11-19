package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.identity.AuthSession;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(OutputCaptureExtension.class)
class SyncWebSocketHandlerTest {
    @Test void malformedCursorRequiresSnapshotWithoutRegistering() throws Exception {
        var identity = mock(IdentityService.class);
        var sync = mock(SyncService.class);
        var socket = socket("invalid");
        when(identity.authenticateSession(any())).thenReturn(binding());
        new SyncWebSocketHandler(identity, sync).afterConnectionEstablished(socket);
        verify(socket).close(new CloseStatus(1008, "RESYNC_REQUIRED"));
        verifyNoInteractions(sync);
    }

    @Test void internalSetupFailureUsesServerCloseWithoutLoggingSecrets(CapturedOutput output) throws Exception {
        var identity = mock(IdentityService.class);
        var sync = mock(SyncService.class);
        var socket = socket("0");
        when(identity.authenticateSession(any())).thenReturn(binding());
        doThrow(new IllegalStateException("private-token private-database-url"))
                .when(sync).connect(any(), any(), anyLong());
        new SyncWebSocketHandler(identity, sync).afterConnectionEstablished(socket);
        verify(socket).close(CloseStatus.SERVER_ERROR);
        assertThat(output.getAll()).contains("Sync setup failed: category=IllegalStateException")
                .doesNotContain("private-token", "private-database-url", "access_token");
    }

    @Test void arbitraryApiFailureCannotExposeItsCodeAsCloseReason() throws Exception {
        var identity = mock(IdentityService.class);
        var sync = mock(SyncService.class);
        var socket = socket("0");
        when(identity.authenticateSession(any())).thenThrow(
                new ApiException(HttpStatus.UNAUTHORIZED, "private-token", "private-message", false));
        new SyncWebSocketHandler(identity, sync).afterConnectionEstablished(socket);
        verify(socket).close(new CloseStatus(1008, "ACCESS_TOKEN_INVALID"));
        verifyNoInteractions(sync);
    }

    private WebSocketSession socket(String cursor) {
        var socket = mock(WebSocketSession.class);
        when(socket.getUri()).thenReturn(URI.create("ws://127.0.0.1/api/v1/ws?access_token=private-token&cursor=" + cursor));
        when(socket.getAttributes()).thenReturn(new HashMap<>());
        return socket;
    }

    private AuthSession binding() {
        return new AuthSession("session", "user", "device", "unused", "unused",
                Instant.now().plusSeconds(60), Instant.now().plusSeconds(60), null, 1L);
    }
}
