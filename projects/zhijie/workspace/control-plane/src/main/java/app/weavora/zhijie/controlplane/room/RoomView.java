package app.weavora.zhijie.controlplane.room;

import app.weavora.zhijie.controlplane.group.GroupMember;

public record RoomView(
        String id, String groupId, String name, String description, String gameName,
        String ownerUserId, RoomOpenState openState, RoomLifecycle lifecycle, String currentActivityId,
        boolean passwordProtected, boolean passwordRequired
) {
    public static RoomView from(Room room, GroupMember member) {
        return new RoomView(room.id(), room.groupId(), room.name(), room.description(), room.gameName(),
                room.ownerUserId(), room.openState(), room.lifecycle(), room.currentActivityId(), room.passwordHash() != null,
                room.requiresPassword(member));
    }
}
