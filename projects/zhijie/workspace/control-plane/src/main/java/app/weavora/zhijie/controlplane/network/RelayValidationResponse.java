package app.weavora.zhijie.controlplane.network;

public record RelayValidationResponse(
        int version, String ticket, String activityId, Long activityGeneration, Long memberGeneration,
        String leaseId, String deviceId, String userId, String sourceIpv4, Long policyRevision,
        boolean allowRelay, long expiresAtUnixMillis
) {
}
