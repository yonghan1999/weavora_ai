package app.weavora.zhijie.controlplane.activity;

import java.time.Instant;

public record IpAllocation(String activityId, String userId, String ipv4, Instant allocatedAt, Instant releasedAt) {
}
