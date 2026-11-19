package app.weavora.zhijie.controlplane.revocation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DisabledAccountScheduler {
    private final DisabledAccountMapper mapper;
    private final DisabledAccountRevocation revocation;

    public DisabledAccountScheduler(DisabledAccountMapper mapper, DisabledAccountRevocation revocation) {
        this.mapper = mapper;
        this.revocation = revocation;
    }

    @Scheduled(fixedDelay = 1000)
    public void reconcile() {
        for (var candidate : mapper.candidates()) revocation.revoke(candidate);
        for (String userId : mapper.sessionsToRevoke()) revocation.revokeSessions(userId);
    }
}
