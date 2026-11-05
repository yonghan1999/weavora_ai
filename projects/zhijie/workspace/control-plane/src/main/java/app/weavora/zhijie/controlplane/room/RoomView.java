package app.weavora.zhijie.controlplane.room;

public record RoomView(
        String id, String groupId, String name, String description, String gameName,
        String ownerUserId, RoomOpenState openState, RoomLifecycle lifecycle, String currentActivityId,
        boolean passwordProtected
) {
    public static RoomView from(Room room) {
        return new RoomView(room.id(), room.groupId(), room.name(), room.description(), room.gameName(),
                room.ownerUserId(), room.openState(), room.lifecycle(), room.currentActivityId(), room.passwordHash() != null);
    }
}
