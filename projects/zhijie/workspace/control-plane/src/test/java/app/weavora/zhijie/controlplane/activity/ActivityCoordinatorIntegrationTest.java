package app.weavora.zhijie.controlplane.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.group.Group;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.AccountStatus;
import app.weavora.zhijie.controlplane.identity.IdentityRepository;
import app.weavora.zhijie.controlplane.identity.UserAccount;
import app.weavora.zhijie.controlplane.room.Activity;
import app.weavora.zhijie.controlplane.room.Room;
import app.weavora.zhijie.controlplane.room.RoomOpenState;
import app.weavora.zhijie.controlplane.room.RoomService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

class ActivityCoordinatorIntegrationTest {
    private static EmbeddedPostgres postgres;
    private static ConfigurableApplicationContext context;

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.start();
        SpringApplication application = new SpringApplication(ControlPlaneApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        context = application.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password="
        );
    }

    @AfterAll
    static void stop() throws Exception {
        if (context != null) context.close();
        if (postgres != null) postgres.close();
    }

    @Test
    void twelveAccountsOccupyRoomAndThirteenthWaitsForGraceRelease() {
        Fixture fixture = fixture(13);
        ActivityCoordinator coordinator = context.getBean(ActivityCoordinator.class);
        List<JoinResult> joined = new ArrayList<>();
        for (String userId : fixture.userIds().subList(0, 12)) {
            joined.add(coordinator.join(userId, fixture.roomId(), "device-" + userId, null, "join-" + userId));
        }
        assertThat(joined).extracting(JoinResult::virtualIpv4).doesNotHaveDuplicates().hasSize(12);
        String waiting = fixture.userIds().get(12);
        assertThatThrownBy(() -> coordinator.join(waiting, fixture.roomId(), "device-waiting", null, "join-waiting"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CAPACITY_FULL");

        String disconnected = fixture.userIds().get(0);
        coordinator.disconnect(disconnected, fixture.activityId(), "device-" + disconnected);
        assertThatThrownBy(() -> coordinator.join(waiting, fixture.roomId(), "device-waiting", null, "join-waiting-2"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CAPACITY_FULL");
        ActivityMember member = context.getBean(ActivityRepository.class).findMember(fixture.activityId(), disconnected).orElseThrow();
        context.getBean(ActivityRepository.class).upsertMember(new ActivityMember(member.activityId(), member.userId(),
                member.deviceId(), member.memberGeneration(), MemberState.GRACE, member.joinedAt(), Instant.now().minusSeconds(1)));
        assertThat(coordinator.cleanupExpiredGrace()).isEqualTo(1);
        assertThat(coordinator.join(waiting, fixture.roomId(), "device-waiting", null, "join-waiting-3").virtualIpv4())
                .isNotBlank();
    }

    @Test
    void concurrentDeviceTransferLeavesOnePointerAndOneCurrentMemberGeneration() throws Exception {
        Fixture fixture = fixture(1);
        ActivityCoordinator coordinator = context.getBean(ActivityCoordinator.class);
        String userId = fixture.userIds().get(0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<JoinResult> first = () -> coordinator.join(userId, fixture.roomId(), "device-a", null, "device-a");
            Callable<JoinResult> second = () -> coordinator.join(userId, fixture.roomId(), "device-b", null, "device-b");
            Future<JoinResult> firstResult = executor.submit(first);
            Future<JoinResult> secondResult = executor.submit(second);
            assertThat(firstResult.get().activityId()).isEqualTo(fixture.activityId());
            assertThat(secondResult.get().activityId()).isEqualTo(fixture.activityId());
        } finally {
            executor.shutdownNow();
        }

        ActivityRepository repository = context.getBean(ActivityRepository.class);
        AccountActivity pointer = repository.findAccountActivity(userId).orElseThrow();
        ActivityMember member = repository.findMember(fixture.activityId(), userId).orElseThrow();
        assertThat(pointer.deviceId()).isEqualTo(member.deviceId());
        assertThat(pointer.memberGeneration()).isEqualTo(member.memberGeneration());
        assertThat(repository.countOccupied(fixture.activityId())).isEqualTo(1);
        assertThat(repository.findLatestLease(fixture.activityId(), userId).orElseThrow().memberGeneration())
                .isEqualTo(member.memberGeneration());
    }

    @Test
    void failedTargetKeepsOldActivityAndSuccessfulSwitchRevokesOldLease() {
        Fixture fixture = fixture(1);
        String userId = fixture.userIds().get(0);
        RoomService rooms = context.getBean(RoomService.class);
        Room secondRoom = rooms.createRoom(userId, fixture.groupId(), "目标房", "", "", null);
        Activity secondActivity = rooms.startActivity(userId, secondRoom.id());
        ActivityCoordinator coordinator = context.getBean(ActivityCoordinator.class);
        JoinResult original = coordinator.join(userId, fixture.roomId(), "device-a", null, "original");
        rooms.setOpenState(userId, secondRoom.id(), RoomOpenState.PAUSED);

        assertThatThrownBy(() -> coordinator.join(userId, secondRoom.id(), "device-a", null, "paused-target"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ROOM_PAUSED");
        ActivityRepository repository = context.getBean(ActivityRepository.class);
        assertThat(repository.findAccountActivity(userId).orElseThrow().activityId()).isEqualTo(original.activityId());

        rooms.setOpenState(userId, secondRoom.id(), RoomOpenState.OPEN);
        JoinResult switched = coordinator.join(userId, secondRoom.id(), "device-a", null, "switch-target");
        assertThat(switched.activityId()).isEqualTo(secondActivity.id());
        assertThat(repository.findLease(original.leaseId()).orElseThrow().revokedAt()).isNotNull();
        assertThat(repository.findMember(original.activityId(), userId).orElseThrow().state()).isEqualTo(MemberState.REVOKED);
        assertThat(repository.findAddress(original.activityId(), userId).orElseThrow().releasedAt()).isNotNull();
    }

    @Test
    void graceRecoveryKeepsIpAndNewJoinCreatesFreshGenerationWithPreferredHistory() {
        Fixture fixture = fixture(1);
        String userId = fixture.userIds().get(0);
        ActivityCoordinator coordinator = context.getBean(ActivityCoordinator.class);
        JoinResult initial = coordinator.join(userId, fixture.roomId(), "device-a", null, "initial");
        coordinator.disconnect(userId, fixture.activityId(), "device-a");
        JoinResult resumed = coordinator.resume(userId, fixture.activityId(), "device-a");

        assertThat(resumed.virtualIpv4()).isEqualTo(initial.virtualIpv4());
        assertThat(resumed.leaseId()).isEqualTo(initial.leaseId());
        assertThat(resumed.memberGeneration()).isEqualTo(initial.memberGeneration());

        coordinator.leave(userId, fixture.activityId());
        JoinResult rejoined = coordinator.join(userId, fixture.roomId(), "device-a", null, "rejoined");
        assertThat(rejoined.virtualIpv4()).isEqualTo(initial.virtualIpv4());
        assertThat(rejoined.leaseId()).isNotEqualTo(initial.leaseId());
        assertThat(rejoined.memberGeneration()).isGreaterThan(initial.memberGeneration());
        assertThat(repository().findLease(initial.leaseId()).orElseThrow().revokedAt()).isNotNull();
    }

    private ActivityRepository repository() {
        return context.getBean(ActivityRepository.class);
    }

    private Fixture fixture(int userCount) {
        String prefix = UUID.randomUUID().toString().replace("-", "");
        IdentityRepository identities = context.getBean(IdentityRepository.class);
        List<String> userIds = new ArrayList<>();
        for (int index = 0; index < userCount; index++) {
            String userId = prefix + "-" + index;
            identities.insertUser(new UserAccount(userId, prefix + "-" + index + "@example.com",
                    AccountStatus.ACTIVE, "not-used", Instant.now()));
            userIds.add(userId);
        }
        Group group = context.getBean(GroupService.class).createGroup(userIds.get(0), "容量测试", null, "");
        RoomService rooms = context.getBean(RoomService.class);
        Room room = rooms.createRoom(userIds.get(0), group.id(), "测试房", "", "", null);
        Activity activity = rooms.startActivity(userIds.get(0), room.id());
        for (String userId : userIds.subList(1, userIds.size())) {
            context.getBean(GroupService.class).acceptInvitation(userId,
                    context.getBean(GroupService.class).invite(userIds.get(0), group.id(), userId).id());
        }
        return new Fixture(group.id(), room.id(), activity.id(), userIds);
    }

    private record Fixture(String groupId, String roomId, String activityId, List<String> userIds) {
    }
}
