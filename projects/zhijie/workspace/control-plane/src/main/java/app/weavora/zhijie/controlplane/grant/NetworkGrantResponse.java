package app.weavora.zhijie.controlplane.grant;

import java.time.Instant;
import java.util.List;

public record NetworkGrantResponse(
        String grantId, String activityId, Long memberGeneration, String leaseId, String networkTicket,
        Long policyRevision, List<String> allowedPaths, String virtualIpv4, String activityCidr, Instant expiresAt
) {
}
