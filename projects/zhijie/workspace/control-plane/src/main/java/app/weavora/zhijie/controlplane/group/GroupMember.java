package app.weavora.zhijie.controlplane.group;

import java.time.Instant;

public record GroupMember(
        String groupId, String userId, GroupRole role, MemberStatus status, Instant joinedAt, Instant leftAt
) {
}
