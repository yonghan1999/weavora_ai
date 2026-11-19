package app.weavora.zhijie.controlplane.revocation;

import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.IdentityRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每次只锁一个群及一个账号，避免批量清理与跨群换房形成反向锁顺序。 */
@Service
public class DisabledAccountRevocation {
    private final DisabledAccountMapper candidates;
    private final ActivityRepository activities;
    private final GroupService groups;
    private final app.weavora.zhijie.controlplane.group.GroupRepository groupRepository;
    private final IdentityRepository identities;
    private final RevocationService revocation;
    private final Clock clock;

    public DisabledAccountRevocation(DisabledAccountMapper candidates, ActivityRepository activities, GroupService groups,
                                     IdentityRepository identities, RevocationService revocation, Clock clock,
                                     app.weavora.zhijie.controlplane.group.GroupRepository groupRepository) {
        this.candidates = candidates;
        this.activities = activities;
        this.groups = groups;
        this.identities = identities;
        this.revocation = revocation;
        this.clock = clock;
        this.groupRepository = groupRepository;
    }

    @Transactional
    public void revoke(DisabledAccountMapper.Candidate candidate) {
        groups.lockGroup(candidate.groupId());
        if (groupRepository.findGroup(candidate.groupId()).orElseThrow().status()
                != app.weavora.zhijie.controlplane.group.GroupStatus.ACTIVE) return;
        activities.lockUser(candidate.userId());
        if (!"DISABLED".equals(candidates.status(candidate.userId()))) return;
        groups.invalidateDisabledMember(candidate.groupId(), candidate.userId());
        for (var member : activities.listActiveByGroup(candidate.groupId())) {
            if (member.userId().equals(candidate.userId())) revocation.revokeMember(member, "ACCOUNT_DISABLED");
        }
        identities.revokeAllSessions(candidate.userId(), clock.instant());
    }

    @Transactional
    public void revokeSessions(String userId) {
        activities.lockUser(userId);
        if ("DISABLED".equals(candidates.status(userId))) identities.revokeAllSessions(userId, clock.instant());
    }
}
