package app.weavora.zhijie.controlplane.revocation;

import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.identity.IdentityRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivitySessionRevocation {
    private final ActivityRepository activities;
    private final IdentityRepository identities;
    private final RevocationService revocation;
    private final Clock clock;

    public ActivitySessionRevocation(ActivityRepository activities, IdentityRepository identities, RevocationService revocation, Clock clock) {
        this.activities = activities;
        this.identities = identities;
        this.revocation = revocation;
        this.clock = clock;
    }

    @Transactional
    public void revokeInvalid(String userId) {
        activities.lockUser(userId);
        var pointer = activities.findAccountActivity(userId).orElse(null);
        if (pointer == null) return;
        if (pointer.authSessionId() != null && identities.findSessionForUpdate(pointer.authSessionId())
                .filter(session -> session.allowsActivity(userId, pointer.deviceId(), clock.instant())).isPresent()) return;
        activities.findMember(pointer.activityId(), userId)
                .filter(member -> member.memberGeneration().equals(pointer.memberGeneration()) && member.deviceId().equals(pointer.deviceId()))
                .ifPresent(member -> revocation.revokeMember(member, "AUTH_SESSION_INVALID"));
    }
}
