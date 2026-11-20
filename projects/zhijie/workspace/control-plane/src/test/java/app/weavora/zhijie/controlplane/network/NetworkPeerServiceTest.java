package app.weavora.zhijie.controlplane.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.activity.ChannelState;
import app.weavora.zhijie.controlplane.activity.ConnectionLease;
import app.weavora.zhijie.controlplane.grant.ActivityGrant;
import app.weavora.zhijie.controlplane.grant.GrantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NetworkPeerServiceTest {
    @Test
    void peerExpiryNeverExceedsCurrentLeaseOrTicket() {
        var now = Instant.parse("2026-11-20T00:00:00Z");
        var key = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
        var grant = new ActivityGrant("grant", "activity", "remote", "lease", 2L, 3L,
                "NETWORK", "hash", key, "DIRECT,RELAY", now.plusSeconds(60), null, now);
        var caller = new ActivityGrant("caller", "activity", "local", "local-lease", 1L, 3L,
                "NETWORK", "caller-hash", key, "DIRECT,RELAY", now.plusSeconds(60), null, now);
        var access = mock(NetworkAccessService.class);
        var mapper = mock(NetworkPeerMapper.class);
        var grants = mock(GrantRepository.class);
        var service = new NetworkPeerService(access, mapper, grants, mock(ActivityRepository.class), Clock.systemUTC());
        when(access.validate("ticket")).thenReturn(new NetworkAccessService.Access(caller, null, "10.231.0.1", List.of("DIRECT", "RELAY")));
        when(mapper.list("activity")).thenReturn(List.of(new NetworkPeerMapper.Endpoint("activity", "remote", "grant", 2L, "127.0.0.1:41002")));
        when(grants.findById("grant")).thenReturn(Optional.of(grant));
        for (long lifetime : List.of(10L, 120L)) {
            var lease = new ConnectionLease("lease", "activity", "remote", "device", 1L, 2L, 3L,
                    ChannelState.CONNECTED, ChannelState.CONNECTED, now.plusSeconds(lifetime), null);
            when(access.validateGrant(grant)).thenReturn(new NetworkAccessService.Access(grant, lease, "10.231.0.2", List.of("DIRECT", "RELAY")));
            assertThat(service.list("local", "activity", "ticket")).singleElement().satisfies(peer -> {
                assertThat(peer.userId()).isEqualTo("remote");
                assertThat(peer.memberGeneration()).isEqualTo(2L);
                assertThat(peer.leaseId()).isEqualTo("lease");
                assertThat(peer.expiresAt()).isEqualTo(now.plusSeconds(Math.min(lifetime, 60)));
            });
        }
    }
}
