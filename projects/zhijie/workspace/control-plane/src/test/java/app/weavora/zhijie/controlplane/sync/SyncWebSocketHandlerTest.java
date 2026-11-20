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
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

@ExtendWith(OutputCaptureExtension.class)
class SyncWebSocketHandlerTest {
    @Test void nativeTransportWriteDeadlineIsSetBeforeAuthenticatedReplay() throws Exception {
        var identity = mock(IdentityService.class);
        var sync = mock(SyncService.class);
        var socket = mock(NativeWebSocketSession.class);
        var transport = mock(jakarta.websocket.Session.class);
        var properties = new HashMap<String, Object>();
        var binding = binding();
        when(socket.getNativeSession(jakarta.websocket.Session.class)).thenReturn(transport);
        when(transport.getUserProperties()).thenReturn(properties);
        when(socket.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        when(socket.getUri()).thenReturn(URI.create("ws://127.0.0.1/api/v1/ws?cursor=0"));
        when(socket.getAttributes()).thenReturn(new HashMap<>());
        when(identity.authenticateSession(any())).thenReturn(binding);
        doAnswer(call -> {
            assertThat(properties).containsEntry("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 2000L);
            return null;
        }).when(sync).connect(binding, socket, 0);

        new SyncWebSocketHandler(identity, sync).afterConnectionEstablished(socket);

        verify(sync).connect(binding, socket, 0);
        verify(socket, never()).close(any(CloseStatus.class));
    }

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

    @Test void presentHeaderNeverFallsBackToLegacyQueryCredential() throws Exception {
        var identity = mock(IdentityService.class);
        var sync = mock(SyncService.class);
        var socket = socket("0");
        socket.getHandshakeHeaders().setBearerAuth("rejected");
        when(identity.authenticateSession("Bearer rejected")).thenThrow(
                new ApiException(HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID", "invalid", false));
        new SyncWebSocketHandler(identity, sync).afterConnectionEstablished(socket);
        verify(identity).authenticateSession("Bearer rejected");
        verifyNoMoreInteractions(identity);
        verify(socket).close(new CloseStatus(1008, "ACCESS_TOKEN_INVALID"));
        verifyNoInteractions(sync);
    }

    @Test void ambiguousAuthorizationNeverRegisters() throws Exception {
        var identity = mock(IdentityService.class);
        var sync = mock(SyncService.class);
        var socket = socket("0");
        socket.getHandshakeHeaders().add(HttpHeaders.AUTHORIZATION, "Bearer first");
        socket.getHandshakeHeaders().add(HttpHeaders.AUTHORIZATION, "Bearer second");
        new SyncWebSocketHandler(identity, sync).afterConnectionEstablished(socket);
        verify(socket).close(new CloseStatus(1008, "ACCESS_TOKEN_INVALID"));
        verifyNoInteractions(identity, sync);
    }

    private WebSocketSession socket(String cursor) {
        var socket = mock(WebSocketSession.class);
        when(socket.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        when(socket.getUri()).thenReturn(URI.create("ws://127.0.0.1/api/v1/ws?access_token=private-token&cursor=" + cursor));
        when(socket.getAttributes()).thenReturn(new HashMap<>());
        return socket;
    }

    private AuthSession binding() {
        return new AuthSession("session", "user", "device", "unused", "unused",
                Instant.now().plusSeconds(60), Instant.now().plusSeconds(60), null, 1L);
    }
}
