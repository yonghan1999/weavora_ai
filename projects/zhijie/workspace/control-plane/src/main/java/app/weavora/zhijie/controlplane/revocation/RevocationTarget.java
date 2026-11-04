package app.weavora.zhijie.controlplane.revocation;

import app.weavora.zhijie.controlplane.sync.OutboxEvent;

public interface RevocationTarget {
    boolean supports(String eventType);
    void deliver(OutboxEvent event);
}
