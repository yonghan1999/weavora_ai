package app.weavora.zhijie.controlplane.activity;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.group.GroupMember;
import app.weavora.zhijie.controlplane.group.GroupRole;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import app.weavora.zhijie.controlplane.room.Activity;
import app.weavora.zhijie.controlplane.room.ActivityState;
import app.weavora.zhijie.controlplane.room.Room;
import app.weavora.zhijie.controlplane.room.RoomLifecycle;
import app.weavora.zhijie.controlplane.room.RoomOpenState;
import app.weavora.zhijie.controlplane.room.RoomService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityCoordinator {
    public static final Duration GRACE_PERIOD = Duration.ofSeconds(60);
    public static final int ROOM_CAPACITY = 12;
    public static final String ACTIVITY_CIDR = "10.231.0.0/27";
    private static final long CURRENT_POLICY_REVISION = 1L;

    private final ActivityRepository repository;
    private final IdentityService identity;
    private final GroupService groups;
    private final RoomService rooms;
    private final Clock clock;
    private final app.weavora.zhijie.controlplane.revocation.RevocationService revocation;

    public ActivityCoordinator(ActivityRepository repository, IdentityService identity, GroupService groups,
                               RoomService rooms, Clock clock,
                               app.weavora.zhijie.controlplane.revocation.RevocationService revocation) {
        this.repository = repository;
        this.identity = identity;
        this.groups = groups;
        this.rooms = rooms;
        this.clock = clock;
        this.revocation = revocation;
    }

    @Transactional
    public JoinResult join(String userId, String roomId, String deviceId, String password, String operationId) {
        identity.requireActive(userId);
        repository.lockUser(userId);
        AccountActivity previous = repository.findAccountActivity(userId).orElse(null);
        ConnectionLease previousLease = previous == null ? null :
                repository.findLatestLease(previous.activityId(), userId).orElse(null);
        Room room = repository.findRoomForUpdate(roomId).orElseThrow(this::roomUnavailable);
        if (room.lifecycle() != RoomLifecycle.AVAILABLE) throw roomUnavailable();
        GroupMember groupMember = groups.requireMembership(room.groupId(), userId);
        Activity activity = currentActivity(room);

        if (previous != null && previous.activityId().equals(activity.id()) && previous.deviceId().equals(deviceId)) {
            ActivityMember existing = repository.findMember(activity.id(), userId).orElse(null);
            if (existing != null && existing.state() == MemberState.ACTIVE) return currentResult(existing);
            if (existing != null && existing.state() == MemberState.GRACE) return resumeInternal(existing);
        }
        if (room.openState() != RoomOpenState.OPEN) throw conflict("ROOM_PAUSED", "房间已暂停新加入");
        boolean passwordExempt = groupMember.role() == GroupRole.OWNER || groupMember.role() == GroupRole.ADMIN
                || room.ownerUserId().equals(userId);
        if (!passwordExempt && !rooms.passwordMatches(room, password)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ROOM_PASSWORD_INVALID", "房间密码错误", false);
        }
        boolean alreadyCounted = previous != null && previous.activityId().equals(activity.id());
        if (!alreadyCounted && repository.countOccupied(activity.id()) >= ROOM_CAPACITY) {
            throw conflict("CAPACITY_FULL", "房间已达到 12 人上限");
        }

        Instant now = clock.instant();
        String ipv4 = allocateAddress(room, activity, userId, now);
        ActivityMember priorMember = repository.findMember(activity.id(), userId).orElse(null);
        long generation = priorMember == null ? 1L : priorMember.memberGeneration() + 1;
        ActivityMember member = new ActivityMember(activity.id(), userId, deviceId, generation, MemberState.ACTIVE,
                priorMember == null ? now : priorMember.joinedAt(), null);
        ConnectionLease lease = new ConnectionLease(UUID.randomUUID().toString(), activity.id(), userId, deviceId,
                activity.activityGeneration(), generation, CURRENT_POLICY_REVISION, ChannelState.CONNECTING,
                ChannelState.CONNECTING, now.plus(GRACE_PERIOD), null);
        repository.upsertMember(member);
        repository.insertLease(lease);
        repository.upsertAccountActivity(new AccountActivity(userId, activity.id(), deviceId, generation, operationId));
        revokePrevious(previous, previousLease, member, now);
        return result(member, lease, ipv4);
    }

    @Transactional
    public JoinResult resume(String userId, String activityId, String deviceId) {
        identity.requireActive(userId);
        repository.lockUser(userId);
        AccountActivity pointer = repository.findAccountActivity(userId).orElseThrow(this::resumeUnavailable);
        if (!pointer.activityId().equals(activityId) || !pointer.deviceId().equals(deviceId)) throw resumeUnavailable();
        Activity activity = repository.findActivityForUpdate(activityId).orElseThrow(this::resumeUnavailable);
        if (activity.state() != ActivityState.ACTIVE) throw resumeUnavailable();
        ActivityMember member = repository.findMember(activityId, userId).orElseThrow(this::resumeUnavailable);
        if (!member.deviceId().equals(deviceId)) throw resumeUnavailable();
        if (member.state() == MemberState.ACTIVE) return currentResult(member);
        if (member.state() != MemberState.GRACE || clock.instant().isAfter(member.graceExpiresAt())) throw resumeUnavailable();
        return resumeInternal(member);
    }

    @Transactional
    public void disconnect(String userId, String activityId, String deviceId) {
        repository.lockUser(userId);
        AccountActivity pointer = repository.findAccountActivity(userId).orElseThrow(this::resumeUnavailable);
        if (!pointer.activityId().equals(activityId) || !pointer.deviceId().equals(deviceId)) return;
        ActivityMember member = repository.findMember(activityId, userId).orElseThrow(this::resumeUnavailable);
        if (member.state() != MemberState.ACTIVE) return;
        Instant now = clock.instant();
        repository.upsertMember(new ActivityMember(member.activityId(), member.userId(), member.deviceId(),
                member.memberGeneration(), MemberState.GRACE, member.joinedAt(), now.plus(GRACE_PERIOD)));
        ConnectionLease lease = currentLease(member);
        repository.updateLease(new ConnectionLease(lease.id(), lease.activityId(), lease.userId(), lease.deviceId(),
                lease.activityGeneration(), lease.memberGeneration(), lease.policyRevision(), ChannelState.DISCONNECTED,
                ChannelState.DISCONNECTED, now.plus(GRACE_PERIOD), null));
    }

    @Transactional
    public void leave(String userId, String activityId) {
        repository.lockUser(userId);
        AccountActivity pointer = repository.findAccountActivity(userId).orElse(null);
        if (pointer == null || !pointer.activityId().equals(activityId)) return;
        ActivityMember member = repository.findMember(activityId, userId).orElse(null);
        if (member == null) {
            repository.deleteAccountActivity(userId);
            return;
        }
        leaveInternal(member);
    }

    @Transactional
    public int cleanupExpiredGrace() {
        List<ActivityMember> expired = repository.listExpiredGrace(clock.instant());
        for (ActivityMember member : expired) {
            repository.lockUser(member.userId());
            ActivityMember current = repository.findMember(member.activityId(), member.userId()).orElse(null);
            if (current != null && current.state() == MemberState.GRACE
                    && !clock.instant().isBefore(current.graceExpiresAt())) leaveInternal(current);
        }
        return expired.size();
    }

    public List<ActivityMember> listMembers(String userId, String activityId) {
        ActivityMember actor = repository.findMember(activityId, userId).orElseThrow(this::roomUnavailable);
        if (actor.state() != MemberState.ACTIVE && actor.state() != MemberState.GRACE) throw roomUnavailable();
        identity.requireActive(userId);
        return repository.listMembers(activityId).stream()
                .filter(member -> member.state() == MemberState.ACTIVE || member.state() == MemberState.GRACE).toList();
    }

    private JoinResult resumeInternal(ActivityMember member) {
        Instant now = clock.instant();
        Activity activity = repository.findActivityForUpdate(member.activityId()).orElseThrow(this::resumeUnavailable);
        if (activity.state() != ActivityState.ACTIVE) throw resumeUnavailable();
        ActivityMember active = new ActivityMember(member.activityId(), member.userId(), member.deviceId(),
                member.memberGeneration(), MemberState.ACTIVE, member.joinedAt(), null);
        repository.upsertMember(active);
        ConnectionLease lease = currentLease(member);
        ConnectionLease resumed = new ConnectionLease(lease.id(), lease.activityId(), lease.userId(), lease.deviceId(),
                lease.activityGeneration(), lease.memberGeneration(), lease.policyRevision(), ChannelState.CONNECTING,
                ChannelState.CONNECTING, now.plus(GRACE_PERIOD), null);
        repository.updateLease(resumed);
        return currentResult(active);
    }

    private String allocateAddress(Room room, Activity activity, String userId, Instant now) {
        IpAllocation existing = repository.findAddress(activity.id(), userId).orElse(null);
        if (existing != null && existing.releasedAt() == null) return existing.ipv4();
        Set<String> used = new HashSet<>(repository.listActiveAddresses(activity.id()));
        String preferred = repository.findIpHistory(room.id(), userId).map(IpHistory::lastIpv4).orElse(null);
        String selected = preferred != null && !used.contains(preferred) ? preferred : null;
        for (int host = 1; selected == null && host <= ROOM_CAPACITY; host++) {
            String candidate = "10.231.0." + host;
            if (!used.contains(candidate)) selected = candidate;
        }
        if (selected == null) throw conflict("IP_CONFLICT", "活动内没有可用虚拟地址");
        repository.upsertAddress(new IpAllocation(activity.id(), userId, selected, now, null));
        repository.upsertIpHistory(new IpHistory(room.id(), userId, selected, activity.id(), now));
        return selected;
    }

    private void revokePrevious(AccountActivity previous, ConnectionLease previousLease, ActivityMember replacement, Instant now) {
        if (previous == null) return;
        if (previousLease != null && previousLease.revokedAt() == null) {
            repository.updateLease(new ConnectionLease(previousLease.id(), previousLease.activityId(), previousLease.userId(),
                    previousLease.deviceId(), previousLease.activityGeneration(), previousLease.memberGeneration(),
                    previousLease.policyRevision(), ChannelState.REVOKED, ChannelState.REVOKED,
                    previousLease.expiresAt(), now));
            revocation.revokeLease(previousLease, "ACTIVITY_OR_DEVICE_CHANGED");
        }
        if (!previous.activityId().equals(replacement.activityId())) {
            ActivityMember oldMember = repository.findMember(previous.activityId(), previous.userId()).orElse(null);
            if (oldMember != null) revocation.revokeMember(oldMember, "ACTIVITY_SWITCHED");
        }
    }

    private void leaveInternal(ActivityMember member) {
        revocation.revokeMember(member, "LEAVE_OR_GRACE_EXPIRED");
        repository.upsertMember(new ActivityMember(member.activityId(), member.userId(), member.deviceId(),
                member.memberGeneration(), MemberState.LEFT, member.joinedAt(), null));
    }

    private Activity currentActivity(Room room) {
        if (room.currentActivityId() == null) throw conflict("ACTIVITY_NOT_ACTIVE", "房间尚未开始活动");
        Activity activity = repository.findActivityForUpdate(room.currentActivityId()).orElseThrow(this::roomUnavailable);
        if (activity.state() != ActivityState.ACTIVE) throw conflict("ACTIVITY_NOT_ACTIVE", "房间活动已结束");
        return activity;
    }

    private JoinResult currentResult(ActivityMember member) {
        ConnectionLease lease = currentLease(member);
        IpAllocation address = repository.findAddress(member.activityId(), member.userId()).orElseThrow(this::roomUnavailable);
        return result(member, lease, address.ipv4());
    }

    private ConnectionLease currentLease(ActivityMember member) {
        ConnectionLease lease = repository.findLatestLease(member.activityId(), member.userId()).orElseThrow(this::roomUnavailable);
        if (lease.revokedAt() != null || !lease.memberGeneration().equals(member.memberGeneration())) throw resumeUnavailable();
        return lease;
    }

    private JoinResult result(ActivityMember member, ConnectionLease lease, String ipv4) {
        Activity activity = repository.findActivityForUpdate(member.activityId()).orElseThrow(this::roomUnavailable);
        return new JoinResult(member.activityId(), activity.activityGeneration(), member.memberGeneration(), member.deviceId(),
                lease.id(), lease.policyRevision(), lease.voiceState(), lease.networkState(), ipv4, ACTIVITY_CIDR);
    }

    private ApiException roomUnavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_AVAILABLE", "房间或活动不可用", false);
    }
    private ApiException resumeUnavailable() {
        return new ApiException(HttpStatus.CONFLICT, "GENERATION_STALE", "恢复资格无效、已过期或设备不匹配", false);
    }
    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, false);
    }
}
