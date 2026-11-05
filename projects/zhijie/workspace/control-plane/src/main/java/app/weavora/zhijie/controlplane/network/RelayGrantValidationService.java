package app.weavora.zhijie.controlplane.network;

import app.weavora.zhijie.controlplane.activity.ActivityMember;
import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.activity.ConnectionLease;
import app.weavora.zhijie.controlplane.activity.MemberState;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.ActivityGrant;
import app.weavora.zhijie.controlplane.grant.GrantRepository;
import app.weavora.zhijie.controlplane.identity.CredentialProtector;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RelayGrantValidationService {
    private final GrantRepository grants;
    private final ActivityRepository activities;
    private final CredentialProtector credentials;
    private final Clock clock;

    public RelayGrantValidationService(GrantRepository grants, ActivityRepository activities,
                                       CredentialProtector credentials, Clock clock) {
        this.grants = grants;
        this.activities = activities;
        this.credentials = credentials;
        this.clock = clock;
    }

    public RelayValidationResponse validate(String ticket) {
        ActivityGrant grant = grants.findByTokenHash(credentials.hashToken(ticket))
                .filter(item -> item.kind().equals("NETWORK") && item.revokedAt() == null
                        && clock.instant().isBefore(item.expiresAt()))
                .orElseThrow(this::invalidGrant);
        ActivityMember member = activities.findMember(grant.activityId(), grant.userId())
                .filter(item -> item.memberGeneration().equals(grant.memberGeneration()))
                .filter(item -> item.state() == MemberState.ACTIVE || item.state() == MemberState.GRACE)
                .orElseThrow(this::invalidGrant);
        ConnectionLease lease = activities.findLease(grant.leaseId()).filter(item -> item.revokedAt() == null)
                .orElseThrow(this::invalidGrant);
        String address = activities.findAddress(grant.activityId(), grant.userId()).orElseThrow(this::invalidGrant).ipv4();
        boolean allowRelay = grant.allowedPaths() != null && grant.allowedPaths().contains("RELAY");
        return new RelayValidationResponse(ticket, grant.activityId(), member.memberGeneration(), lease.id(),
                grant.userId(), address, grant.policyRevision(), allowRelay);
    }

    private ApiException invalidGrant() {
        return new ApiException(HttpStatus.FORBIDDEN, "RELAY_GRANT_INVALID", "中继票据无效或已撤销", false);
    }
}
