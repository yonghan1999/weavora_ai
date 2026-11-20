package app.weavora.zhijie.controlplane.sync;

import app.weavora.zhijie.controlplane.identity.IdentityService;
import app.weavora.zhijie.controlplane.common.ApiException;
import java.net.URI;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class SyncWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(SyncWebSocketHandler.class);
    private static final String USER_ID = "userId";
    private final IdentityService identity;
    private final SyncService sync;

    public SyncWebSocketHandler(IdentityService identity, SyncService sync) {
        this.identity = identity;
        this.sync = sync;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            if (session instanceof NativeWebSocketSession nativeSession) {
                var transport = nativeSession.getNativeSession(jakarta.websocket.Session.class);
                if (transport != null) {
                    transport.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 2000L);
                }
            }
            URI uri = session.getUri();
            Map<String, String> parameters = UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap();
            var headers = session.getHandshakeHeaders().get(HttpHeaders.AUTHORIZATION);
            if (headers != null && headers.size() != 1) {
                session.close(new CloseStatus(1008, "ACCESS_TOKEN_INVALID"));
                return;
            }
            var binding = identity.authenticateSession(headers == null
                    ? "Bearer " + parameters.getOrDefault("access_token", "") : headers.getFirst());
            long cursor;
            try { cursor = Long.parseLong(parameters.getOrDefault("cursor", "0")); }
            catch (NumberFormatException invalid) {
                session.close(new CloseStatus(1008, "RESYNC_REQUIRED"));
                return;
            }
            session.getAttributes().put(USER_ID, binding.userId());
            sync.connect(binding, session, cursor);
        } catch (ApiException exception) {
            String reason = "RESYNC_REQUIRED".equals(exception.code()) ? "RESYNC_REQUIRED" : "ACCESS_TOKEN_INVALID";
            session.close(new CloseStatus(1008, reason));
        } catch (Exception exception) {
            // 不输出异常正文、堆栈或 URI；其中可能包含查询凭据。
            log.warn("Sync setup failed: category={}", exception.getClass().getSimpleName());
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object userId = session.getAttributes().get(USER_ID);
        if (userId != null) sync.unregister(userId.toString(), session);
    }
}
