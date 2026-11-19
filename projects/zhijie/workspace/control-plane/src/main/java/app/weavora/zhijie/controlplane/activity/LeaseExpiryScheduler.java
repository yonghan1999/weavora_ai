package app.weavora.zhijie.controlplane.activity;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LeaseExpiryScheduler {
    private final ActivityCoordinator activities;

    public LeaseExpiryScheduler(ActivityCoordinator activities) {
        this.activities = activities;
    }

    @Scheduled(fixedDelay = 1000)
    public void expire() {
        activities.markExpiredConnections();
        activities.cleanupExpiredGrace();
    }
}
