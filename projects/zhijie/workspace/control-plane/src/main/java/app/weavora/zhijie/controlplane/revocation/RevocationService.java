package app.weavora.zhijie.controlplane.revocation;

import app.weavora.zhijie.controlplane.activity.AccountActivity;
import app.weavora.zhijie.controlplane.activity.ActivityMember;
import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.activity.ChannelState;
import app.weavora.zhijie.controlplane.activity.ConnectionLease;
import app.weavora.zhijie.controlplane.activity.MemberState;
import app.weavora.zhijie.controlplane.grant.GrantRepository;
import app.weavora.zhijie.controlplane.room.GroupMemberStatusEvent;
import app.weavora.zhijie.controlplane.sync.OutboxService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RevocationService {
    private final ActivityRepository activities;
    private final GrantRepository grants;
    private final OutboxService outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RevocationService(ActivityRepository activities, GrantRepository grants, OutboxService outbox,
                             ObjectMapper objectMapper, Clock clock) {
        this.activities = activities;
        this.grants = grants;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void revokeMember(ActivityMember member, String reason) {
        activities.lockUser(member.userId());
        ActivityMember current = activities.findMember(member.activityId(), member.userId()).orElse(null);
        if (current == null || current.state() == MemberState.REVOKED || current.state() == MemberState.LEFT) return;
        Instant now = clock.instant();
        activities.upsertMember(new ActivityMember(current.activityId(), current.userId(), current.deviceId(),
                current.memberGeneration(), MemberState.REVOKED, current.joinedAt(), null));
        ConnectionLease lease = activities.findLatestLease(current.activityId(), current.userId()).orElse(null);
        if (lease != null && lease.revokedAt() == null) {
            activities.updateLease(new ConnectionLease(lease.id(), lease.activityId(), lease.userId(), lease.deviceId(),
                    lease.activityGeneration(), lease.memberGeneration(), lease.policyRevision(), ChannelState.REVOKED,
                    ChannelState.REVOKED, lease.expiresAt(), now));
        }
        grants.revokeMember(current.activityId(), current.userId(), now);
        activities.releaseAddress(current.activityId(), current.userId(), now);
        AccountActivity pointer = activities.findAccountActivity(current.userId()).orElse(null);
        if (pointer != null && pointer.activityId().equals(current.activityId())
                && pointer.memberGeneration().equals(current.memberGeneration())) {
            activities.deleteAccountActivity(current.userId());
        }
        String groupId = activities.findGroupId(current.activityId());
        String payload = payload(current, reason);
        outbox.record(groupId, current.activityId(), current.userId(), "voice.revoked", payload,
                current.memberGeneration(), lease == null ? null : lease.policyRevision());
        outbox.record(groupId, current.activityId(), current.userId(), "network.revoked", payload,
                current.memberGeneration(), lease == null ? null : lease.policyRevision());
        outbox.record(groupId, current.activityId(), current.userId(), "lease.revoked", payload,
                current.memberGeneration(), lease == null ? null : lease.policyRevision());
    }

    @Transactional
    public void revokeLease(ConnectionLease lease, String reason) {
        Instant now = clock.instant();
        grants.revokeLease(lease.id(), now);
        String groupId = activities.findGroupId(lease.activityId());
        String payload = payload(new ActivityMember(lease.activityId(), lease.userId(), lease.deviceId(),
                lease.memberGeneration(), MemberState.REVOKED, now, null), reason);
        for (String type : List.of("voice.revoked", "network.revoked", "lease.revoked")) {
            outbox.record(groupId, lease.activityId(), lease.userId(), type, payload,
                    lease.memberGeneration(), lease.policyRevision());
        }
    }

    @Transactional
    public void revokeActivity(String activityId, String reason) {
        List<ActivityMember> members = activities.listMembers(activityId);
        for (ActivityMember member : members) {
            if (member.state() == MemberState.ACTIVE || member.state() == MemberState.GRACE) revokeMember(member, reason);
        }
    }

    @EventListener
    @Transactional
    public void onMemberLost(GroupMemberStatusEvent event) {
        for (ActivityMember member : activities.listActiveByGroup(event.groupId())) {
            if (member.userId().equals(event.userId())) revokeMember(member, "GROUP_MEMBERSHIP_LOST");
        }
    }

    private String payload(ActivityMember member, String reason) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "user_id", member.userId(), "device_id", member.deviceId(),
                    "member_generation", member.memberGeneration(), "reason", reason
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize revocation event", exception);
        }
    }
}
