package app.weavora.zhijie.controlplane.sync;

import app.weavora.zhijie.controlplane.identity.IdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class SyncWebSocketHandler extends TextWebSocketHandler {
    private static final String USER_ID = "userId";
    private final IdentityService identity;
    private final SyncService sync;
    private final ObjectMapper objectMapper;

    public SyncWebSocketHandler(IdentityService identity, SyncService sync, ObjectMapper objectMapper) {
        this.identity = identity;
        this.sync = sync;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            URI uri = session.getUri();
            Map<String, String> parameters = UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap();
            String userId = identity.authenticate("Bearer " + parameters.getOrDefault("access_token", "")).id();
            long cursor = Long.parseLong(parameters.getOrDefault("cursor", "0"));
            session.getAttributes().put(USER_ID, userId);
            sync.register(userId, session);
            for (SyncEvent event : sync.history(userId, cursor)) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
            }
        } catch (Exception exception) {
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object userId = session.getAttributes().get(USER_ID);
        if (userId != null) sync.unregister(userId.toString(), session);
    }
}
