package app.weavora.zhijie.controlplane.revocation;

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
        var externalEvents = external.dispatchPending();
        return new DispatchResult(synchronizedEvents.delivered() + externalEvents.delivered(),
                synchronizedEvents.failed() + externalEvents.failed());
    }

    public record DispatchResult(int delivered, int failed) {
    }
}
