package app.weavora.zhijie.controlplane.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.sync.OutboxService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NetworkPolicyServiceTest {
    @Test
    void policyChangesUseMonotonicRevisionsAndRollbackCreatesNewRevision() {
        Map<Long, NetworkPolicyRevision> revisions = new HashMap<>();
        NetworkPolicyRepository repository = new NetworkPolicyRepository() {
            public Optional<NetworkPolicyRevision> current() {
                return revisions.values().stream().max((a, b) -> a.revision().compareTo(b.revision()));
            }
            public Optional<NetworkPolicyRevision> findByRevision(long revision) { return Optional.ofNullable(revisions.get(revision)); }
            public void insert(NetworkPolicyRevision revision) { revisions.put(revision.revision(), revision); }
        };
        NetworkPolicyService service = new NetworkPolicyService(repository, mock(OutboxService.class), Clock.systemUTC());
        revisions.put(1L, new NetworkPolicyRevision(1L, NetworkPolicyMode.DIRECT_WITH_RELAY_FALLBACK,
                "MIGRATE_WHEN_REQUIRED", Instant.now(), "test"));

        assertThat(service.allowedPaths(NetworkPolicyMode.DIRECT_ONLY)).containsExactly("DIRECT");
        assertThat(service.allowedPaths(NetworkPolicyMode.RELAY_ONLY)).containsExactly("RELAY");
        NetworkPolicyRevision direct = service.update("ops", NetworkPolicyMode.DIRECT_ONLY, 1L);
        assertThat(direct.revision()).isEqualTo(2L);
        NetworkPolicyRevision rollback = service.rollback("ops", 1L, 2L);
        assertThat(rollback.revision()).isEqualTo(3L);
        assertThat(rollback.mode()).isEqualTo(NetworkPolicyMode.DIRECT_WITH_RELAY_FALLBACK);
        assertThatThrownBy(() -> service.update("ops", NetworkPolicyMode.RELAY_ONLY, 2L))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("POLICY_STALE");
    }
}
