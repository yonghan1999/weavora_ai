package app.weavora.zhijie.controlplane.room;

import app.weavora.zhijie.controlplane.group.GroupMember;
import app.weavora.zhijie.controlplane.group.GroupRole;

public record Room(
        String id, String groupId, String name, String description, String gameName, String passwordHash,
        String ownerUserId, RoomOpenState openState, RoomLifecycle lifecycle, String currentActivityId
) {
    public boolean requiresPassword(GroupMember member) {
        return passwordHash != null && member.role() != GroupRole.OWNER && member.role() != GroupRole.ADMIN
                && !ownerUserId.equals(member.userId());
    }
}
