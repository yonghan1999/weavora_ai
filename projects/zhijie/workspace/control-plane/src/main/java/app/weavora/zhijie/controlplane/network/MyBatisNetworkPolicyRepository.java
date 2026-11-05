package app.weavora.zhijie.controlplane.network;

import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisNetworkPolicyRepository implements NetworkPolicyRepository {
    private final NetworkPolicyMapper mapper;
    public MyBatisNetworkPolicyRepository(NetworkPolicyMapper mapper) { this.mapper = mapper; }
    public Optional<NetworkPolicyRevision> current() { return mapper.current(); }
    public Optional<NetworkPolicyRevision> findByRevision(long revision) { return mapper.findByRevision(revision); }
    public void insert(NetworkPolicyRevision revision) { mapper.insert(revision); }
}
