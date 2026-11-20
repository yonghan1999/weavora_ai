package app.weavora.zhijie.controlplane.revocation;

import app.weavora.zhijie.controlplane.sync.ExternalDeliveryChannel;
import org.springframework.stereotype.Service;

@Service
public class RevocationDispatcher {
    private final SyncOutboxDispatcher sync;
    private final ExternalRevocationDispatcher external;

    public RevocationDispatcher(SyncOutboxDispatcher sync, ExternalRevocationDispatcher external) {
        this.sync = sync;
        this.external = external;
    }

    public DispatchResult dispatchPending() {
        var synchronizedEvents = sync.dispatchPending();
        int delivered = synchronizedEvents.delivered();
        int failed = synchronizedEvents.failed();
        for (ExternalDeliveryChannel channel : ExternalDeliveryChannel.values()) {
            var externalEvents = external.dispatchPending(channel);
            delivered += externalEvents.delivered();
            failed += externalEvents.failed();
        }
        return new DispatchResult(delivered, failed);
    }

    public record DispatchResult(int delivered, int failed) {
    }
}
