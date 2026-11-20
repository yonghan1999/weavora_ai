package app.weavora.zhijie.controlplane.network;

import app.weavora.zhijie.controlplane.activity.*;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.ActivityGrant;
import app.weavora.zhijie.controlplane.grant.GrantRepository;
import app.weavora.zhijie.controlplane.identity.CredentialProtector;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import app.weavora.zhijie.controlplane.room.ActivityState;
import app.weavora.zhijie.controlplane.room.RoomRepository;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 直连、Helper 与 Relay 共用权威资格检查；票据本身不代表持续授权。 */
@Service
public class NetworkAccessService {
    private final GrantRepository grants;
    private final ActivityRepository activities;
    private final CredentialProtector credentials;
    private final IdentityService identity;
    private final RoomRepository rooms;
    private final NetworkPolicyService policies;
    private final Clock clock;
    private final app.weavora.zhijie.controlplane.group.GroupService groups;

    public NetworkAccessService(GrantRepository grants, ActivityRepository activities, CredentialProtector credentials,
                                IdentityService identity, RoomRepository rooms, NetworkPolicyService policies, Clock clock,
                                app.weavora.zhijie.controlplane.group.GroupService groups) {
        this.grants = grants;
        this.activities = activities;
        this.credentials = credentials;
        this.identity = identity;
        this.rooms = rooms;
        this.policies = policies;
        this.clock = clock;
        this.groups = groups;
    }

    public Access validate(String ticket) {
        if (ticket == null || ticket.isBlank() || ticket.length() > 512) throw denied();
        var grant = grants.findByTokenHash(credentials.hashToken(ticket))
                .filter(item -> item.kind().equals("NETWORK") && item.revokedAt() == null
                        && clock.instant().isBefore(item.expiresAt())).orElseThrow(this::denied);
        return validateGrant(grant);
    }

    public Access validateGrant(ActivityGrant grant) {
        if (!"NETWORK".equals(grant.kind()) || grant.revokedAt() != null || !clock.instant().isBefore(grant.expiresAt())) throw denied();
        identity.requireActive(grant.userId());
        var activity = rooms.findActivity(grant.activityId()).filter(item -> item.state() == ActivityState.ACTIVE).orElseThrow(this::denied);
        var room = rooms.findRoom(activity.roomId()).filter(item -> item.lifecycle() == app.weavora.zhijie.controlplane.room.RoomLifecycle.AVAILABLE).orElseThrow(this::denied);
        try { groups.requireMembership(room.groupId(), grant.userId()); }
        catch (ApiException exception) { throw denied(); }
        var member = activities.findMember(grant.activityId(), grant.userId())
                .filter(item -> item.memberGeneration().equals(grant.memberGeneration()))
                .filter(item -> item.state() == MemberState.ACTIVE || item.state() == MemberState.GRACE).orElseThrow(this::denied);
        var lease = activities.findLatestLease(grant.activityId(), grant.userId())
                .filter(item -> item.id().equals(grant.leaseId()) && item.revokedAt() == null && clock.instant().isBefore(item.expiresAt()))
                .filter(item -> item.activityGeneration().equals(activity.activityGeneration())
                        && item.memberGeneration().equals(member.memberGeneration()) && item.deviceId().equals(member.deviceId()))
                .orElseThrow(this::denied);
        var pointer = activities.findAccountActivity(grant.userId()).filter(item -> item.activityId().equals(grant.activityId())
                && item.memberGeneration().equals(member.memberGeneration()) && item.deviceId().equals(member.deviceId())).orElseThrow(this::denied);
        try { identity.requireActivitySession(pointer.authSessionId(), grant.userId(), member.deviceId()); }
        catch (ApiException exception) { throw denied(); }
        var address = activities.findAddress(grant.activityId(), grant.userId()).filter(item -> item.releasedAt() == null).orElseThrow(this::denied);
        var policy = policies.current();
        if (!grant.policyRevision().equals(policy.revision())) throw denied();
        return new Access(grant, lease, address.ipv4(), policies.allowedPaths(policy.mode()));
    }

    public ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN, "NETWORK_GRANT_INVALID", "网络资格无效、已过期或已撤销", false);
    }

    public record Access(ActivityGrant grant, ConnectionLease lease, String ipv4, java.util.List<String> allowedPaths) {
    }
}
