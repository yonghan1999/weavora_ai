package app.weavora.zhijie.controlplane.network;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.sync.OutboxService;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NetworkPolicyService {
    private final NetworkPolicyRepository repository;
    private final OutboxService outbox;
    private final Clock clock;

    public NetworkPolicyService(NetworkPolicyRepository repository, OutboxService outbox, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.clock = clock;
    }

    public NetworkPolicyRevision current() {
        return repository.current().orElseThrow(() -> new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "NETWORK_POLICY_UNAVAILABLE", "网络策略尚未初始化", false));
    }

    @Transactional
    public NetworkPolicyRevision update(String actor, NetworkPolicyMode mode, long expectedRevision) {
        NetworkPolicyRevision current = current();
        if (current.revision() != expectedRevision) throw stale();
        NetworkPolicyRevision created = new NetworkPolicyRevision(current.revision() + 1, mode,
                "MIGRATE_WHEN_REQUIRED", clock.instant(), actor);
        repository.insert(created);
        outbox.record(null, null, created.revision().toString(), "network_policy.changed",
                "{\"mode\":\"" + mode + "\"}", null, created.revision());
        return created;
    }

    @Transactional
    public NetworkPolicyRevision rollback(String actor, long targetRevision, long expectedRevision) {
        NetworkPolicyRevision current = current();
        if (current.revision() != expectedRevision) throw stale();
        if (targetRevision >= current.revision()) throw stale();
        NetworkPolicyMode mode = repository.findByRevision(targetRevision)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "POLICY_REVISION_NOT_FOUND", "目标策略修订不存在", false))
                .mode();
        return update(actor, mode, expectedRevision);
    }

    public List<String> allowedPaths(NetworkPolicyMode mode) {
        return switch (mode) {
            case DIRECT_ONLY -> List.of("DIRECT");
            case DIRECT_WITH_RELAY_FALLBACK -> List.of("DIRECT", "RELAY");
            case RELAY_ONLY -> List.of("RELAY");
        };
    }

    private ApiException stale() {
        return new ApiException(HttpStatus.CONFLICT, "POLICY_STALE", "策略修订已变化，请重新获取", true);
    }
}
