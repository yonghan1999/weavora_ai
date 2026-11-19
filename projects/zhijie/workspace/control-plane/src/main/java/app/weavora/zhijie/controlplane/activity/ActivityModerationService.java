package app.weavora.zhijie.controlplane.activity;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.audit.AuditService;
import app.weavora.zhijie.controlplane.group.*;
import app.weavora.zhijie.controlplane.revocation.RevocationService;
import app.weavora.zhijie.controlplane.room.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityModerationService {
    private final ActivityRepository activities;
    private final GroupService groups;
    private final RoomService rooms;
    private final RevocationService revocation;
    private final AuditService audit;

    public ActivityModerationService(ActivityRepository activities, GroupService groups, RoomService rooms, RevocationService revocation, AuditService audit) {
        this.activities = activities;
        this.groups = groups;
        this.rooms = rooms;
        this.revocation = revocation;
        this.audit = audit;
    }

    @Transactional
    public void kick(String actorId, String activityId, String targetId, long memberGeneration) {
        String groupId = activities.findGroupId(activityId);
        if (groupId == null) throw unavailable();
        groups.lockGroup(groupId);
        // 与加入/恢复共用目标账号锁，确认后的迟到请求不能撤销新成员代次。
        activities.lockUser(targetId);
        Activity activity = activities.findActivityForUpdate(activityId).orElseThrow(this::unavailable);
        if (activity.state() != ActivityState.ACTIVE) throw unavailable();
        Room room = rooms.getRoom(actorId, activity.roomId());
        GroupMember actor = groups.requireMembership(groupId, actorId);
        GroupMember target = groups.requireMembership(groupId, targetId);
        boolean allowed = !actorId.equals(targetId) && target.role() != GroupRole.OWNER
                && (actor.role() == GroupRole.OWNER
                    || (target.role() == GroupRole.MEMBER && (actor.role() == GroupRole.ADMIN || room.ownerUserId().equals(actorId))));
        if (!allowed) throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "当前角色不能踢出该成员", false);
        ActivityMember member = activities.findMember(activityId, targetId).orElseThrow(this::unavailable);
        if (member.memberGeneration() != memberGeneration || (member.state() != MemberState.ACTIVE && member.state() != MemberState.GRACE)) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVITY_MEMBER_CHANGED", "成员已离开或重新加入，请刷新成员列表", false);
        }
        revocation.revokeMember(member, "ACTIVITY_MEMBER_KICKED");
        audit.record(groupId, actorId, "ACTIVITY_MEMBER", targetId, "ACTIVITY_MEMBER_KICK", "SUCCESS", activityId);
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_AVAILABLE", "活动成员不可用", false);
    }
}
