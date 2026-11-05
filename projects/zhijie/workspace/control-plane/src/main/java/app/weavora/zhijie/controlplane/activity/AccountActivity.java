package app.weavora.zhijie.controlplane.activity;

public record AccountActivity(
        String userId, String activityId, String deviceId, Long memberGeneration, String operationId
) {
}
