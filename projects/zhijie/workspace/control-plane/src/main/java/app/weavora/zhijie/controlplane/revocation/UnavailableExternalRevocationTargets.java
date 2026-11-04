package app.weavora.zhijie.controlplane.revocation;

import app.weavora.zhijie.controlplane.sync.OutboxEvent;
import org.springframework.stereotype.Component;

@Component
class UnavailableExternalRevocationTargets {
    @Component
    static class Voice implements RevocationTarget {
        public boolean supports(String eventType) { return eventType.equals("voice.revoked"); }
        public void deliver(OutboxEvent event) { throw new IllegalStateException("Voice gateway is not configured"); }
    }

    @Component
    static class Relay implements RevocationTarget {
        public boolean supports(String eventType) { return eventType.equals("network.revoked"); }
        public void deliver(OutboxEvent event) { throw new IllegalStateException("Relay is not configured"); }
    }
}
