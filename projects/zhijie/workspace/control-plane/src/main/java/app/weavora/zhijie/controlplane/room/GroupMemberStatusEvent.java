package app.weavora.zhijie.controlplane.room;

import app.weavora.zhijie.controlplane.group.MemberStatus;

public record GroupMemberStatusEvent(String groupId, String userId, String groupOwnerId, MemberStatus status) {
}
