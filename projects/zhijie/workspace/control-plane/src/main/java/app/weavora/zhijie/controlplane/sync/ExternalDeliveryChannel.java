package app.weavora.zhijie.controlplane.sync;

public enum ExternalDeliveryChannel {
    VOICE("voice.revoked"), NETWORK("network.revoked"), OTHER(null);

    private final String eventType;

    ExternalDeliveryChannel(String eventType) { this.eventType = eventType; }

    public String getEventType() { return eventType; }
}
