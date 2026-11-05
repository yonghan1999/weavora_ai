package app.weavora.zhijie.controlplane.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.weavora.zhijie.controlplane.activity.ActivityMember;
import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.activity.ConnectionLease;
import app.weavora.zhijie.controlplane.activity.IpAllocation;
import app.weavora.zhijie.controlplane.activity.MemberState;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.ActivityGrant;
import app.weavora.zhijie.controlplane.grant.GrantRepository;
import app.weavora.zhijie.controlplane.identity.CredentialProtector;
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
        when(activities.findAddress("activity", "user"))
                .thenReturn(Optional.of(new IpAllocation("activity", "user", "10.231.0.1", Instant.now(), null)));
        RelayGrantValidationService service = new RelayGrantValidationService(grants, activities, credentials, Clock.systemUTC());

        RelayValidationResponse response = service.validate(ticket);
        assertThat(response.allowRelay()).isTrue();
        assertThat(response.sourceIpv4()).isEqualTo("10.231.0.1");
        assertThat(response.memberGeneration()).isEqualTo(2L);
        assertThatThrownBy(() -> service.validate("invalid"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RELAY_GRANT_INVALID");
    }
}
