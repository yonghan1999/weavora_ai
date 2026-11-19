package app.weavora.zhijie.controlplane.room;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.audit.AuditService;
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
    private final app.weavora.zhijie.controlplane.revocation.RevocationService revocation;
    private final AuditService audit;
    private final app.weavora.zhijie.controlplane.sync.ProductSync sync;

    public RoomService(RoomRepository repository, GroupService groups, CredentialProtector credentials, Clock clock,
                       app.weavora.zhijie.controlplane.revocation.RevocationService revocation, AuditService audit,
                       app.weavora.zhijie.controlplane.sync.ProductSync sync) {
        this.repository = repository;
        this.groups = groups;
        this.credentials = credentials;
        this.clock = clock;
        this.revocation = revocation;
        this.audit = audit;
        this.sync = sync;
    }

    @Transactional
    public Room createRoom(String actorId, String groupId, String name, String description, String gameName, String password) {
        groups.lockGroup(groupId);
        GroupMember actor = groups.requireMembership(groupId, actorId);
        if (actor.role() == GroupRole.MEMBER) throw forbidden();
        requireName(name);
        Room room = new Room(UUID.randomUUID().toString(), groupId, name.trim(), value(description), value(gameName),
                password == null || password.isBlank() ? null : credentials.hashPassword(password), actorId,
                RoomOpenState.OPEN, RoomLifecycle.AVAILABLE, null);
        repository.insertRoom(room);
        record(actorId, room, "ROOM_CREATE");
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
        Room room = governanceRoom(roomId);
        requireManager(actorId, room);
        requireName(name);
        Room updated = new Room(room.id(), room.groupId(), name.trim(), value(description), value(gameName),
                password == null || password.isBlank() ? null : credentials.hashPassword(password), room.ownerUserId(),
                room.openState(), room.lifecycle(), room.currentActivityId());
        repository.updateRoom(updated);
        record(actorId, room, "ROOM_UPDATE");
        return updated;
    }

    @Transactional
    public Room assignOwner(String actorId, String roomId, String targetUserId) {
        Room room = governanceRoom(roomId);
        GroupMember actor = groups.requireMembership(room.groupId(), actorId);
        if (actor.role() != GroupRole.OWNER) throw forbidden();
        groups.requireMembership(room.groupId(), targetUserId);
        Room updated = copy(room, targetUserId, room.openState(), room.lifecycle(), room.currentActivityId());
        repository.updateRoom(updated);
        audit.record(room.groupId(), actorId, "ROOM", roomId, "ROOM_OWNER_ASSIGN", "SUCCESS", targetUserId);
        sync.groupChanged(room.groupId(), "room.changed");
        return updated;
    }

    @Transactional
    public Room setOpenState(String actorId, String roomId, RoomOpenState state) {
        Room room = governanceRoom(roomId);
        requireManager(actorId, room);
        Room updated = copy(room, room.ownerUserId(), state, room.lifecycle(), room.currentActivityId());
        repository.updateRoom(updated);
        record(actorId, room, state == RoomOpenState.OPEN ? "ROOM_OPEN" : "ROOM_PAUSE");
        return updated;
    }

    @Transactional
    public Activity startActivity(String actorId, String roomId) {
        Room room = governanceRoom(roomId);
        requireManager(actorId, room);
        if (room.currentActivityId() != null) {
            Activity current = repository.findActivity(room.currentActivityId()).orElse(null);
            if (current != null && current.state() == ActivityState.ACTIVE) return current;
        }
        Activity activity = new Activity(UUID.randomUUID().toString(), roomId, 1L, ActivityState.ACTIVE, clock.instant(), null);
        repository.insertActivity(activity);
        repository.updateRoom(copy(room, room.ownerUserId(), room.openState(), room.lifecycle(), activity.id()));
        audit.record(room.groupId(), actorId, "ACTIVITY", activity.id(), "ACTIVITY_START", "SUCCESS", UUID.randomUUID().toString());
        sync.groupChanged(room.groupId(), "activity.changed");
        return activity;
    }

    @Transactional
    public void endActivity(String actorId, String activityId) {
        Activity activity = repository.findActivity(activityId).orElseThrow(this::roomUnavailable);
        Room room = governanceRoom(activity.roomId());
        activity = repository.findActivity(activityId).orElseThrow(this::roomUnavailable);
        requireManager(actorId, room);
        endActivityInternal(room, activity);
        if (activity.state() == ActivityState.ACTIVE) {
            audit.record(room.groupId(), actorId, "ACTIVITY", activity.id(), "ACTIVITY_END", "SUCCESS", UUID.randomUUID().toString());
            sync.groupChanged(room.groupId(), "activity.changed");
        }
    }

    @Transactional
    public void deleteRoom(String actorId, String roomId) {
        Room room = governanceRoom(roomId);
        requireManager(actorId, room);
        if (room.currentActivityId() != null) {
            repository.findActivity(room.currentActivityId()).ifPresent(activity -> endActivityInternal(room, activity));
        }
        repository.updateRoom(copy(room, room.ownerUserId(), RoomOpenState.PAUSED, RoomLifecycle.DELETED, null));
        record(actorId, room, "ROOM_DELETE");
    }

    @EventListener
    public void onMemberLost(GroupMemberStatusEvent event) {
        if (event.status() == app.weavora.zhijie.controlplane.group.MemberStatus.ACTIVE) return;
        for (Room room : repository.listGroupRooms(event.groupId())) {
            if (room.lifecycle() == RoomLifecycle.AVAILABLE && room.ownerUserId().equals(event.userId())) {
                String groupOwner = event.groupOwnerId();
                repository.updateRoom(copy(room, groupOwner, room.openState(), room.lifecycle(), room.currentActivityId()));
            }
        }
    }

    public boolean passwordMatches(Room room, String password) {
        return room.passwordHash() == null || (password != null && credentials.matchesPassword(password, room.passwordHash()));
    }

    @EventListener
    @Transactional
    public void onGroupDissolved(app.weavora.zhijie.controlplane.group.GroupDissolvedEvent event) {
        for (Room room : repository.listGroupRooms(event.groupId())) {
            repository.findActiveActivityByRoom(room.id()).ifPresent(activity -> endActivityInternal(room, activity));
            repository.updateRoom(copy(room, room.ownerUserId(), RoomOpenState.PAUSED, room.lifecycle(), null));
        }
    }

    private void endActivityInternal(Room room, Activity activity) {
        if (activity.state() == ActivityState.ACTIVE) {
            repository.updateActivity(new Activity(activity.id(), activity.roomId(), activity.activityGeneration() + 1,
                    ActivityState.ENDED, activity.startedAt(), clock.instant()));
            revocation.revokeActivity(activity.id(), "ACTIVITY_ENDED_OR_ROOM_DELETED");
        }
        if (activity.id().equals(room.currentActivityId())) {
            repository.updateRoom(copy(room, room.ownerUserId(), room.openState(), room.lifecycle(), null));
        }
    }

    private Room availableRoom(String roomId) {
        Room room = repository.findRoom(roomId).orElseThrow(this::roomUnavailable);
        if (room.lifecycle() != RoomLifecycle.AVAILABLE) throw roomUnavailable();
        return room;
    }

    private Room governanceRoom(String roomId) {
        Room room = availableRoom(roomId);
        groups.lockGroup(room.groupId());
        return availableRoom(roomId);
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

    private void record(String actorId, Room room, String action) {
        audit.record(room.groupId(), actorId, "ROOM", room.id(), action, "SUCCESS", UUID.randomUUID().toString());
        sync.groupChanged(room.groupId(), "room.changed");
    }

    private String value(String value) { return value == null ? "" : value; }
    private ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "当前角色不能管理该房间", false);
    }
    private ApiException roomUnavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_AVAILABLE", "房间或活动不可用", false);
    }
}
