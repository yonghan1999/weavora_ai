package app.weavora.zhijie.controlplane.group;

import java.time.Instant;

public record PendingGroupInvitation(
        String id, String groupId, String groupName, String inviterId, String inviteeId, Instant createdAt
) {
}
