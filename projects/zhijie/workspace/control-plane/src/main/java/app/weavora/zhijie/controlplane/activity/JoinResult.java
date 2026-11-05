package app.weavora.zhijie.controlplane.activity;

public record JoinResult(
        String activityId, Long activityGeneration, Long memberGeneration, String deviceId, String leaseId,
        Long policyRevision, ChannelState voiceState, ChannelState networkState, String virtualIpv4, String activityCidr
) {
}
