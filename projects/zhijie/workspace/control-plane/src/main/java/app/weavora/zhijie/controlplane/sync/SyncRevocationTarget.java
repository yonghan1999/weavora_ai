package app.weavora.zhijie.controlplane.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class SyncRevocationTarget {
    private final SyncService sync;
    private final ObjectMapper objectMapper;

    public SyncRevocationTarget(SyncService sync, ObjectMapper objectMapper) {
        this.sync = sync;
        this.objectMapper = objectMapper;
    }

    public void deliver(OutboxEvent event) {
        if (event.type().endsWith(".revoked")) {
            try {
                JsonNode payload = objectMapper.readTree(event.payload());
                if (payload.hasNonNull("user_id")) sync.closeUser(payload.get("user_id").asText());
            } catch (Exception exception) {
                throw new IllegalStateException("Invalid revocation payload", exception);
            }
        }
        sync.broadcast(event);
    }
}
