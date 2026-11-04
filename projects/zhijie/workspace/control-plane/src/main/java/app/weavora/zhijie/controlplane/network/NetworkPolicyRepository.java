package app.weavora.zhijie.controlplane.network;

import java.util.Optional;

public interface NetworkPolicyRepository {
    Optional<NetworkPolicyRevision> current();
    Optional<NetworkPolicyRevision> findByRevision(long revision);
    void insert(NetworkPolicyRevision revision);
}
