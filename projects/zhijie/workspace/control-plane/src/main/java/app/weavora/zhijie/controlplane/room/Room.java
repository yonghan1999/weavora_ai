package app.weavora.zhijie.controlplane.room;

public record Room(
        String id, String groupId, String name, String description, String gameName, String passwordHash,
        String ownerUserId, RoomOpenState openState, RoomLifecycle lifecycle, String currentActivityId
) {
}
