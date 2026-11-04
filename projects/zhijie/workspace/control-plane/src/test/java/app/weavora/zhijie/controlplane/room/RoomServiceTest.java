package app.weavora.zhijie.controlplane.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.group.GroupMember;
import app.weavora.zhijie.controlplane.group.GroupRole;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.group.MemberStatus;
import app.weavora.zhijie.controlplane.identity.CredentialProtector;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoomServiceTest {
    private InMemoryRoomRepository repository;
    private RoomService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRoomRepository();
        GroupService groups = mock(GroupService.class);
        when(groups.requireMembership(anyString(), anyString())).thenAnswer(invocation -> {
            String userId = invocation.getArgument(1);
            GroupRole role = userId.equals("owner") ? GroupRole.OWNER : userId.equals("admin") ? GroupRole.ADMIN : GroupRole.MEMBER;
            return new GroupMember(invocation.getArgument(0), userId, role, MemberStatus.ACTIVE, Instant.now(), null);
        });
        CredentialProtector credentials = mock(CredentialProtector.class);
        when(credentials.hashPassword(anyString())).thenReturn("argon2id-hash");
        when(credentials.matchesPassword("secret", "argon2id-hash")).thenReturn(true);
        service = new RoomService(repository, groups, credentials, Clock.systemUTC());
    }

    @Test
    void onlyGroupManagersCreateRoomsAndResponsesNeverExposePasswordHash() {
        Room room = service.createRoom("admin", "group", "游戏房", "描述", "游戏", "secret");
        assertThat(RoomView.from(room).passwordProtected()).isTrue();
        assertThat(RoomView.from(room).toString()).doesNotContain("argon2id-hash");
        assertThat(service.passwordMatches(room, "secret")).isTrue();
        assertThatThrownBy(() -> service.createRoom("member", "group", "越权房", "", "", null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CURRENT_GRANT_REQUIRED");
    }

    @Test
    void endingActivityClearsMembershipPointerButPreservesRoomAndPauseState() {
        Room room = service.createRoom("owner", "group", "游戏房", "", "", null);
        Activity activity = service.startActivity("owner", room.id());
        assertThat(service.startActivity("owner", room.id())).isEqualTo(activity);
        service.setOpenState("owner", room.id(), RoomOpenState.PAUSED);
        service.endActivity("owner", activity.id());

        Room persisted = repository.findRoom(room.id()).orElseThrow();
        assertThat(persisted.currentActivityId()).isNull();
        assertThat(persisted.openState()).isEqualTo(RoomOpenState.PAUSED);
        assertThat(persisted.lifecycle()).isEqualTo(RoomLifecycle.AVAILABLE);
        assertThat(repository.findActivity(activity.id()).orElseThrow().state()).isEqualTo(ActivityState.ENDED);
        assertThat(repository.findActivity(activity.id()).orElseThrow().activityGeneration()).isEqualTo(2L);
    }

    @Test
    void deletingRoomEndsActivityAndRejectsAllFurtherUse() {
        Room room = service.createRoom("owner", "group", "游戏房", "", "", null);
        Activity activity = service.startActivity("owner", room.id());
        service.deleteRoom("owner", room.id());

        assertThat(repository.findActivity(activity.id()).orElseThrow().state()).isEqualTo(ActivityState.ENDED);
        assertThatThrownBy(() -> service.getRoom("owner", room.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.setOpenState("owner", room.id(), RoomOpenState.OPEN)).isInstanceOf(ApiException.class);
    }

    @Test
    void groupOwnerCanAssignOwnerAndMemberLossTriggersTakeoverOnlyForOwnedRooms() {
        Room owned = service.createRoom("owner", "group", "待接管", "", "", null);
        Room other = service.createRoom("owner", "group", "其他", "", "", null);
        service.assignOwner("owner", other.id(), "member");
        assertThat(repository.findRoom(other.id()).orElseThrow().ownerUserId()).isEqualTo("member");

        service.onMemberLost(new GroupMemberStatusEvent("group", "member", "owner", MemberStatus.REMOVED));
        assertThat(repository.findRoom(other.id()).orElseThrow().ownerUserId()).isEqualTo("owner");
        assertThat(repository.findRoom(owned.id()).orElseThrow().ownerUserId()).isEqualTo("owner");
    }

    private static class InMemoryRoomRepository implements RoomRepository {
        private final Map<String, Room> rooms = new HashMap<>();
        private final Map<String, Activity> activities = new HashMap<>();
        public void insertRoom(Room room) { rooms.put(room.id(), room); }
        public Optional<Room> findRoom(String roomId) { return Optional.ofNullable(rooms.get(roomId)); }
        public List<Room> listGroupRooms(String groupId) { return rooms.values().stream().filter(r -> r.groupId().equals(groupId)).toList(); }
        public void updateRoom(Room room) { rooms.put(room.id(), room); }
        public void insertActivity(Activity activity) { activities.put(activity.id(), activity); }
        public Optional<Activity> findActivity(String activityId) { return Optional.ofNullable(activities.get(activityId)); }
        public Optional<Activity> findActiveActivityByRoom(String roomId) {
            return activities.values().stream().filter(a -> a.roomId().equals(roomId) && a.state() == ActivityState.ACTIVE).findFirst();
        }
        public void updateActivity(Activity activity) { activities.put(activity.id(), activity); }
    }
}
