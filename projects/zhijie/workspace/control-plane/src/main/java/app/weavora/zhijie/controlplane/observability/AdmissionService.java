package app.weavora.zhijie.controlplane.observability;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.GrantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdmissionService {
    private final GrantRepository grants;
    private final int voiceLimit;
    private final int networkLimit;
    private final ObservationService observations;

    public AdmissionService(GrantRepository grants,
                            @Value("${zhijie.capacity.voice:100}") int voiceLimit,
                            @Value("${zhijie.capacity.network:1000}") int networkLimit,
                            ObservationService observations) {
        this.grants = grants;
        this.voiceLimit = voiceLimit;
        this.networkLimit = networkLimit;
        this.observations = observations;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void admitVoice(String leaseId) {
        grants.lockChannelAdmission(3);
        check(grants.countOtherVoiceLeases(leaseId), voiceLimit, "VOICE_CAPACITY_LIMITED");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void admitNetwork(String leaseId) {
        grants.lockChannelAdmission(2);
        check(grants.countOtherNetworkLeases(leaseId), networkLimit, "NETWORK_CAPACITY_LIMITED");
    }

    private void check(int occupied, int limit, String code) {
        if (occupied >= limit) {
            observations.recordApiResult("capacity_rejected");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, "当前通道容量已满，请稍后重试", true);
        }
        observations.recordApiResult("accepted");
    }
}
