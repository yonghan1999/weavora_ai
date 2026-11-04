package app.weavora.zhijie.controlplane.network;

import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NetworkPolicyMapper {
    Optional<NetworkPolicyRevision> current();
    Optional<NetworkPolicyRevision> findByRevision(long revision);
    void insert(NetworkPolicyRevision revision);
}
