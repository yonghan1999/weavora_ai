package app.weavora.zhijie.controlplane.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.weavora.zhijie.controlplane.activity.ActivityMember;
import app.weavora.zhijie.controlplane.activity.AccountActivity;
import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.activity.ConnectionLease;
import app.weavora.zhijie.controlplane.activity.IpAllocation;
import app.weavora.zhijie.controlplane.activity.MemberState;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.ActivityGrant;
import app.weavora.zhijie.controlplane.grant.GrantRepository;
import app.weavora.zhijie.controlplane.identity.CredentialProtector;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import app.weavora.zhijie.controlplane.room.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RelayGrantValidationServiceTest {
    @Test
    void relayValidationRequiresCurrentGrantLeaseAndAllowedPath() {
        CredentialProtector credentials = new CredentialProtector();
        String ticket = credentials.randomSecret();
        ActivityGrant grant = new ActivityGrant("grant", "activity", "user", "lease", 2L, 3L,
                "NETWORK", credentials.hashToken(ticket), "public", "DIRECT,RELAY",
                Instant.now().plusSeconds(60), null, Instant.now());
        GrantRepository grants = mock(GrantRepository.class);
        ActivityRepository activities = mock(ActivityRepository.class);
        when(grants.findByTokenHash(credentials.hashToken(ticket))).thenReturn(Optional.of(grant));
        when(activities.findMember("activity", "user")).thenReturn(Optional.of(new ActivityMember("activity", "user",
                "device", 2L, MemberState.ACTIVE, Instant.now(), null)));
        ConnectionLease lease = new ConnectionLease("lease", "activity", "user", "device", 1L, 2L, 3L,
                app.weavora.zhijie.controlplane.activity.ChannelState.CONNECTED,
                app.weavora.zhijie.controlplane.activity.ChannelState.CONNECTED,
                Instant.now().plusSeconds(60), null);
        when(activities.findLease("lease")).thenReturn(Optional.of(lease));
        when(activities.findLatestLease("activity", "user")).thenReturn(Optional.of(lease));
        when(activities.findAccountActivity("user")).thenReturn(Optional.of(new AccountActivity("user", "activity", "device", 2L, "join")));
        when(activities.findAddress("activity", "user"))
                .thenReturn(Optional.of(new IpAllocation("activity", "user", "10.231.0.1", Instant.now(), null)));
        var rooms = mock(RoomRepository.class);
        when(rooms.findActivity("activity")).thenReturn(Optional.of(new Activity("activity", "room", 1L, ActivityState.ACTIVE, Instant.now(), null)));
        when(rooms.findRoom("room")).thenReturn(Optional.of(new Room("room", "group", "房间", "", "", null, "user", RoomOpenState.OPEN, RoomLifecycle.AVAILABLE, "activity")));
        var policies = mock(NetworkPolicyService.class);
        when(policies.current()).thenReturn(new NetworkPolicyRevision(3L, NetworkPolicyMode.DIRECT_WITH_RELAY_FALLBACK, "MIGRATE_WHEN_REQUIRED", Instant.now(), "test"));
        when(policies.allowedPaths(NetworkPolicyMode.DIRECT_WITH_RELAY_FALLBACK)).thenReturn(java.util.List.of("DIRECT", "RELAY"));
        RelayGrantValidationService service = new RelayGrantValidationService(new NetworkAccessService(grants, activities, credentials,
                mock(IdentityService.class), rooms, policies, Clock.systemUTC(), mock(app.weavora.zhijie.controlplane.group.GroupService.class)));

        RelayValidationResponse response = service.validate(ticket);
        assertThat(response.allowRelay()).isTrue();
        assertThat(response.sourceIpv4()).isEqualTo("10.231.0.1");
        assertThat(response.memberGeneration()).isEqualTo(2L);
        assertThatThrownBy(() -> service.validate("invalid"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RELAY_GRANT_INVALID");
    }
}
