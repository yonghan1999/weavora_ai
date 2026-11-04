package app.weavora.zhijie.controlplane.sync;

import app.weavora.zhijie.controlplane.revocation.RevocationTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class SyncRevocationTarget implements RevocationTarget {
    private final SyncService sync;
    private final ObjectMapper objectMapper;

    public SyncRevocationTarget(SyncService sync, ObjectMapper objectMapper) {
        this.sync = sync;
        this.objectMapper = objectMapper;
    }

    public boolean supports(String eventType) { return true; }

    public void deliver(OutboxEvent event) {
        sync.broadcast(event);
        if (event.type().endsWith(".revoked")) {
            try {
                JsonNode payload = objectMapper.readTree(event.payload());
                if (payload.hasNonNull("user_id")) sync.closeUser(payload.get("user_id").asText());
            } catch (Exception exception) {
                throw new IllegalStateException("Invalid revocation payload", exception);
            }
        }
    }
}
