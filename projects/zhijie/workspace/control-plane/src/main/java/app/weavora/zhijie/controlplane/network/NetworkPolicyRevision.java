package app.weavora.zhijie.controlplane.network;

import java.time.Instant;

public record NetworkPolicyRevision(
        Long revision, NetworkPolicyMode mode, String existingConnectionAction, Instant effectiveAt, String actorRef
) {
}
