package app.weavora.zhijie.controlplane.grant;

import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import app.weavora.zhijie.controlplane.activity.ActivityMember;
import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.activity.ChannelState;
import app.weavora.zhijie.controlplane.activity.ConnectionLease;
import app.weavora.zhijie.controlplane.activity.IpAllocation;
import app.weavora.zhijie.controlplane.activity.MemberState;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.identity.CredentialProtector;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GrantService {
    private static final Duration GRANT_TTL = Duration.ofMinutes(1);
    private final GrantRepository repository;
    private final ActivityRepository activities;
    private final IdentityService identity;
    private final CredentialProtector credentials;
    private final VoiceGateway voiceGateway;
    private final Clock clock;
    private final String voiceUrl;

    public GrantService(GrantRepository repository, ActivityRepository activities, IdentityService identity,
                        CredentialProtector credentials, VoiceGateway voiceGateway, Clock clock,
                        @org.springframework.beans.factory.annotation.Value("${zhijie.voice.url:ws://127.0.0.1:7880}") String voiceUrl) {
        this.repository = repository;
        this.activities = activities;
        this.identity = identity;
        this.credentials = credentials;
        this.voiceGateway = voiceGateway;
        this.clock = clock;
        this.voiceUrl = voiceUrl;
    }

    @Transactional
    public VoiceGrantResponse voice(String userId, String activityId) {
        ActivityMember member = currentMember(userId, activityId);
        ConnectionLease lease = currentLease(member);
        if (!voiceGateway.isAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VOICE_CAPACITY_LIMITED", "语音服务尚未接入或不可用", true);
        }
        Instant expiresAt = clock.instant().plus(GRANT_TTL);
        String token = voiceGateway.issueToken(activityId, userId, member.memberGeneration(), expiresAt);
        ActivityGrant grant = saveGrant(member, lease, "VOICE", token, null, expiresAt);
        return new VoiceGrantResponse(grant.id(), activityId, member.memberGeneration(), lease.id(), voiceUrl, token, expiresAt);
    }

    @Transactional
    public NetworkGrantResponse network(String userId, String activityId, String publicKey) {
        ActivityMember member = currentMember(userId, activityId);
        ConnectionLease lease = currentLease(member);
        if (publicKey == null || publicKey.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "必须提供本机 WireGuard 公钥", false);
        }
        Instant expiresAt = clock.instant().plus(GRANT_TTL);
        String ticket = credentials.randomSecret();
        ActivityGrant grant = saveGrant(member, lease, "NETWORK", ticket, publicKey, expiresAt);
        IpAllocation address = activities.findAddress(activityId, userId).orElseThrow();
        return new NetworkGrantResponse(grant.id(), activityId, member.memberGeneration(), lease.id(), ticket,
                lease.policyRevision(), List.of("DIRECT", "RELAY"), address.ipv4(), ActivityCoordinator.ACTIVITY_CIDR, expiresAt);
    }

    private ActivityGrant saveGrant(ActivityMember member, ConnectionLease lease, String kind, String token,
                                    String publicKey, Instant expiresAt) {
        ActivityGrant grant = new ActivityGrant(UUID.randomUUID().toString(), member.activityId(), member.userId(), lease.id(),
                member.memberGeneration(), lease.policyRevision(), kind, credentials.hashToken(token), publicKey,
                expiresAt, null, clock.instant());
        repository.insert(grant);
        return grant;
    }

    private ActivityMember currentMember(String userId, String activityId) {
        identity.requireActive(userId);
        ActivityMember member = activities.findMember(activityId, userId).orElseThrow(this::grantUnavailable);
        if (member.state() != MemberState.ACTIVE && member.state() != MemberState.GRACE) throw grantUnavailable();
        return member;
    }

    private ConnectionLease currentLease(ActivityMember member) {
        ConnectionLease lease = activities.findLatestLease(member.activityId(), member.userId()).orElseThrow(this::grantUnavailable);
        if (lease.revokedAt() != null || !lease.memberGeneration().equals(member.memberGeneration())
                || !clock.instant().isBefore(lease.expiresAt())) throw grantUnavailable();
        return lease;
    }

    private ApiException grantUnavailable() {
        return new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "当前活动资格无效或已过期", false);
    }
}
