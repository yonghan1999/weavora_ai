package app.weavora.zhijie.controlplane.revocation;

import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ActivitySessionScheduler {
    private final ActivitySessionMapper sessions;
    private final ActivitySessionRevocation revocation;
    private final Clock clock;

    public ActivitySessionScheduler(ActivitySessionMapper sessions, ActivitySessionRevocation revocation, Clock clock) {
        this.sessions = sessions;
        this.revocation = revocation;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 1000)
    public void reconcile() {
        for (String userId : sessions.invalidUsers(clock.instant())) revocation.revokeInvalid(userId);
    }
}
