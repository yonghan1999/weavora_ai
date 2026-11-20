package app.weavora.zhijie.controlplane.revocation;

import app.weavora.zhijie.controlplane.sync.ExternalDeliveryChannel;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExternalRevocationSchedule {
    private final ExternalRevocationDispatcher dispatcher;

    public ExternalRevocationSchedule(ExternalRevocationDispatcher dispatcher) { this.dispatcher = dispatcher; }

    @Scheduled(fixedDelay = 1000, scheduler = "externalRevocationScheduler")
    public void dispatchVoice() { dispatcher.dispatchPending(ExternalDeliveryChannel.VOICE); }

    @Scheduled(fixedDelay = 1000, scheduler = "externalRevocationScheduler")
    public void dispatchNetwork() { dispatcher.dispatchPending(ExternalDeliveryChannel.NETWORK); }

    @Scheduled(fixedDelay = 1000, scheduler = "externalRevocationScheduler")
    public void dispatchOther() { dispatcher.dispatchPending(ExternalDeliveryChannel.OTHER); }
}
