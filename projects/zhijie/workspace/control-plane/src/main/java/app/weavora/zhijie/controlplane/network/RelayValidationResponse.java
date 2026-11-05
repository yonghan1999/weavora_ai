package app.weavora.zhijie.controlplane.network;

public record RelayValidationResponse(
        String ticket, String activityId, Long memberGeneration, String leaseId, String userId,
        String sourceIpv4, Long policyRevision, boolean allowRelay
) {
}
