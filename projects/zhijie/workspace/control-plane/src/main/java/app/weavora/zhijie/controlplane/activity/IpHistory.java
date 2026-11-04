package app.weavora.zhijie.controlplane.activity;

import java.time.Instant;

public record IpHistory(String roomId, String userId, String lastIpv4, String lastActivityId, Instant updatedAt) {
}
