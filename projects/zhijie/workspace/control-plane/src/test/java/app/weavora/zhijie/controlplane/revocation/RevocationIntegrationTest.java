package app.weavora.zhijie.controlplane.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import app.weavora.zhijie.controlplane.activity.ActivityMember;
import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.activity.MemberState;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.ActivityGrant;
import app.weavora.zhijie.controlplane.grant.GrantRepository;
import app.weavora.zhijie.controlplane.grant.GrantService;
import app.weavora.zhijie.controlplane.grant.NetworkGrantResponse;
import app.weavora.zhijie.controlplane.group.Group;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.AccountStatus;
import app.weavora.zhijie.controlplane.identity.IdentityRepository;
import app.weavora.zhijie.controlplane.identity.UserAccount;
import app.weavora.zhijie.controlplane.room.Activity;
import app.weavora.zhijie.controlplane.room.Room;
import app.weavora.zhijie.controlplane.room.RoomService;
import app.weavora.zhijie.controlplane.sync.OutboxRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

class RevocationIntegrationTest {
    private static EmbeddedPostgres postgres;
    private static ConfigurableApplicationContext context;

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.start();
        SpringApplication application = new SpringApplication(ControlPlaneApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        context = application.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--zhijie.scheduling.enabled=false"
        );
    }

    @AfterAll
    static void stop() throws Exception {
        if (context != null) context.close();
        if (postgres != null) postgres.close();
    }

    @Test
    void revocationIsAtomicAndUnavailableExternalTargetsRemainPending() {
        String userId = UUID.randomUUID().toString();
        context.getBean(IdentityRepository.class).insertUser(new UserAccount(userId, userId + "@example.com",
                AccountStatus.ACTIVE, "not-used", Instant.now()));
        Group group = context.getBean(GroupService.class).createGroup(userId, "撤销测试", null, "");
        RoomService rooms = context.getBean(RoomService.class);
        Room room = rooms.createRoom(userId, group.id(), "撤销房", "", "", null);
        Activity activity = rooms.startActivity(userId, room.id());
        ActivityCoordinator coordinator = context.getBean(ActivityCoordinator.class);
        coordinator.join(userId, room.id(), "device-a", null, "join");

        GrantService grantService = context.getBean(GrantService.class);
        NetworkGrantResponse network = grantService.network(userId, activity.id(), "public-key");
        ActivityGrant stored = context.getBean(GrantRepository.class).findById(network.grantId()).orElseThrow();
        assertThat(stored.tokenHash()).isNotEqualTo(network.networkTicket());
        assertThat(stored.publicKey()).isEqualTo("public-key");
        assertThatThrownBy(() -> grantService.voice(userId, activity.id()))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("VOICE_CAPACITY_LIMITED");

        ActivityRepository activities = context.getBean(ActivityRepository.class);
        ActivityMember member = activities.findMember(activity.id(), userId).orElseThrow();
        context.getBean(RevocationService.class).revokeMember(member, "TEST_REVOCATION");

        assertThat(activities.findMember(activity.id(), userId).orElseThrow().state()).isEqualTo(MemberState.REVOKED);
        assertThat(activities.findLatestLease(activity.id(), userId).orElseThrow().revokedAt()).isNotNull();
        assertThat(activities.findAccountActivity(userId)).isEmpty();
        assertThat(activities.findAddress(activity.id(), userId).orElseThrow().releasedAt()).isNotNull();
        assertThat(context.getBean(GrantRepository.class).findById(network.grantId()).orElseThrow().revokedAt()).isNotNull();
        assertThatThrownBy(() -> grantService.network(userId, activity.id(), "new-key"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CURRENT_GRANT_REQUIRED");
        assertThat(context.getBean(OutboxRepository.class).listAfter(0, 10))
                .extracting("type").containsExactlyInAnyOrder("voice.revoked", "network.revoked", "lease.revoked");

        RevocationDispatcher.DispatchResult result = context.getBean(RevocationDispatcher.class).dispatchPending();
        assertThat(result.delivered()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(context.getBean(OutboxRepository.class).listUndelivered(10)).hasSize(2);
    }
}
