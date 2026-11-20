package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.AuthSession;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(OutputCaptureExtension.class)
class SyncDeliveryDiagnosticsTest {
    @Test void encodingFailureIsSeparatedFromTransportFailureWithoutSecrets(CapturedOutput output) throws Exception {
        var json = mock(ObjectMapper.class);
        when(json.writeValueAsString(any())).thenThrow(new JsonProcessingException("private-encoding-payload") { });
        var socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        var service = connect(json, socket);

        service.broadcast(event());

        assertThat(output.getAll()).contains("category=IllegalStateException operation=encode cause=IO socket_open=true")
                .doesNotContain("private-", "access_token", "Authorization");
        verify(socket, never()).sendMessage(any());
        verify(socket).close(CloseStatus.SERVER_ERROR);
        assertThat(service.sessionCount("user")).isZero();
    }

    @Test void failedSocketWriteKeepsTheIoCategoryWithoutLoggingItsMessage(CapturedOutput output) throws Exception {
        var socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        doThrow(new IOException("private-transport-token")).when(socket).sendMessage(any(TextMessage.class));
        var service = connect(new ObjectMapper().findAndRegisterModules(), socket);

        service.broadcast(event());

        assertThat(output.getAll()).contains("category=IllegalStateException operation=send cause=IO socket_open=true")
                .doesNotContain("private-", "access_token", "Authorization");
        verify(socket).close(CloseStatus.SERVER_ERROR);
        assertThat(service.sessionCount("user")).isZero();
    }

    @Test void transportStateFailureRecordsClosedStateWithoutLoggingSessionDetails(CapturedOutput output) throws Exception {
        var socket = mock(WebSocketSession.class);
        doThrow(new IllegalStateException("private-state-uri")).when(socket).sendMessage(any(TextMessage.class));
        var service = connect(new ObjectMapper().findAndRegisterModules(), socket);

        service.broadcast(event());

        assertThat(output.getAll()).contains("category=IllegalStateException operation=send cause=NONE socket_open=false")
                .doesNotContain("private-", "access_token", "Authorization");
        verify(socket).close(CloseStatus.SERVER_ERROR);
        assertThat(service.sessionCount("user")).isZero();
    }

    private SyncService connect(ObjectMapper json, WebSocketSession socket) throws Exception {
        var service = new SyncService(mock(OutboxRepository.class), mock(GroupService.class),
                mock(ActivityRepository.class), json, mock(IdentityService.class));
        service.connect(new AuthSession("session", "user", "device", "unused", "unused",
                Instant.now().plusSeconds(60), Instant.now().plusSeconds(60), null, 1L), socket, 0);
        return service;
    }

    private OutboxEvent event() {
        return new OutboxEvent(1L, "private-event", null, null, "private-aggregate", "test.changed",
                "private-payload", null, null, Instant.now(), null, 0);
    }
}
