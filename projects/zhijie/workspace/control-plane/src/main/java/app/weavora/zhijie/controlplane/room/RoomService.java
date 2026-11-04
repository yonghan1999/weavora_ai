package app.weavora.zhijie.controlplane.room;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.group.GroupMember;
import app.weavora.zhijie.controlplane.group.GroupRole;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.CredentialProtector;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {
    private final RoomRepository repository;
    private final GroupService groups;
    private final CredentialProtector credentials;
    private final Clock clock;

    public RoomService(RoomRepository repository, GroupService groups, CredentialProtector credentials, Clock clock) {
        this.repository = repository;
        this.groups = groups;
        this.credentials = credentials;
        this.clock = clock;
    }

    @Transactional
    public Room createRoom(String actorId, String groupId, String name, String description, String gameName, String password) {
        GroupMember actor = groups.requireMembership(groupId, actorId);
        if (actor.role() == GroupRole.MEMBER) throw forbidden();
        requireName(name);
        Room room = new Room(UUID.randomUUID().toString(), groupId, name.trim(), value(description), value(gameName),
                password == null || password.isBlank() ? null : credentials.hashPassword(password), actorId,
                RoomOpenState.OPEN, RoomLifecycle.AVAILABLE, null);
        repository.insertRoom(room);
        return room;
    }

    public List<Room> listRooms(String actorId, String groupId) {
        groups.requireMembership(groupId, actorId);
        return repository.listGroupRooms(groupId).stream().filter(room -> room.lifecycle() == RoomLifecycle.AVAILABLE).toList();
    }

    public Room getRoom(String actorId, String roomId) {
        Room room = availableRoom(roomId);
        groups.requireMembership(room.groupId(), actorId);
        return room;
    }

    @Transactional
    public Room updateRoom(String actorId, String roomId, String name, String description, String gameName, String password) {
        Room room = availableRoom(roomId);
        requireManager(actorId, room);
        requireName(name);
        Room updated = new Room(room.id(), room.groupId(), name.trim(), value(description), value(gameName),
                password == null || password.isBlank() ? null : credentials.hashPassword(password), room.ownerUserId(),
                room.openState(), room.lifecycle(), room.currentActivityId());
        repository.updateRoom(updated);
        return updated;
    }

    @Transactional
    public Room assignOwner(String actorId, String roomId, String targetUserId) {
        Room room = availableRoom(roomId);
        GroupMember actor = groups.requireMembership(room.groupId(), actorId);
        if (actor.role() != GroupRole.OWNER) throw forbidden();
        groups.requireMembership(room.groupId(), targetUserId);
        Room updated = copy(room, targetUserId, room.openState(), room.lifecycle(), room.currentActivityId());
        repository.updateRoom(updated);
        return updated;
    }

    @Transactional
    public Room setOpenState(String actorId, String roomId, RoomOpenState state) {
        Room room = availableRoom(roomId);
        requireManager(actorId, room);
        Room updated = copy(room, room.ownerUserId(), state, room.lifecycle(), room.currentActivityId());
        repository.updateRoom(updated);
        return updated;
    }

    @Transactional
    public Activity startActivity(String actorId, String roomId) {
        Room room = availableRoom(roomId);
        requireManager(actorId, room);
        if (room.currentActivityId() != null) {
            Activity current = repository.findActivity(room.currentActivityId()).orElse(null);
            if (current != null && current.state() == ActivityState.ACTIVE) return current;
        }
        Activity activity = new Activity(UUID.randomUUID().toString(), roomId, 1L, ActivityState.ACTIVE, clock.instant(), null);
        repository.insertActivity(activity);
        repository.updateRoom(copy(room, room.ownerUserId(), room.openState(), room.lifecycle(), activity.id()));
        return activity;
    }

    @Transactional
    public void endActivity(String actorId, String activityId) {
        Activity activity = repository.findActivity(activityId).orElseThrow(this::roomUnavailable);
        Room room = availableRoom(activity.roomId());
        requireManager(actorId, room);
        endActivityInternal(room, activity);
    }

    @Transactional
    public void deleteRoom(String actorId, String roomId) {
        Room room = availableRoom(roomId);
        requireManager(actorId, room);
        if (room.currentActivityId() != null) {
            repository.findActivity(room.currentActivityId()).ifPresent(activity -> endActivityInternal(room, activity));
        }
        repository.updateRoom(copy(room, room.ownerUserId(), RoomOpenState.PAUSED, RoomLifecycle.DELETED, null));
    }

    @EventListener
    public void onMemberLost(GroupMemberStatusEvent event) {
        if (event.status() == app.weavora.zhijie.controlplane.group.MemberStatus.ACTIVE) return;
        for (Room room : repository.listGroupRooms(event.groupId())) {
            if (room.lifecycle() == RoomLifecycle.AVAILABLE && room.ownerUserId().equals(event.userId())) {
                String groupOwner = groups.requireMembership(event.groupId(), event.groupOwnerId()).userId();
                repository.updateRoom(copy(room, groupOwner, room.openState(), room.lifecycle(), room.currentActivityId()));
            }
        }
    }

    public boolean passwordMatches(Room room, String password) {
        return room.passwordHash() == null || (password != null && credentials.matchesPassword(password, room.passwordHash()));
    }

    private void endActivityInternal(Room room, Activity activity) {
        if (activity.state() == ActivityState.ACTIVE) {
            repository.updateActivity(new Activity(activity.id(), activity.roomId(), activity.activityGeneration() + 1,
                    ActivityState.ENDED, activity.startedAt(), clock.instant()));
        }
        if (room.currentActivityId() != null) {
            repository.updateRoom(copy(room, room.ownerUserId(), room.openState(), room.lifecycle(), null));
        }
    }

    private Room availableRoom(String roomId) {
        Room room = repository.findRoom(roomId).orElseThrow(this::roomUnavailable);
        if (room.lifecycle() != RoomLifecycle.AVAILABLE) throw roomUnavailable();
        return room;
    }

    private void requireManager(String actorId, Room room) {
        GroupMember actor = groups.requireMembership(room.groupId(), actorId);
        if (!room.ownerUserId().equals(actorId) && actor.role() == GroupRole.MEMBER) throw forbidden();
    }

    private Room copy(Room room, String owner, RoomOpenState state, RoomLifecycle lifecycle, String activityId) {
        return new Room(room.id(), room.groupId(), room.name(), room.description(), room.gameName(), room.passwordHash(),
                owner, state, lifecycle, activityId);
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "房间名称不能为空", false);
        }
    }

    private String value(String value) { return value == null ? "" : value; }
    private ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "当前角色不能管理该房间", false);
    }
    private ApiException roomUnavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_AVAILABLE", "房间或活动不可用", false);
    }
}
