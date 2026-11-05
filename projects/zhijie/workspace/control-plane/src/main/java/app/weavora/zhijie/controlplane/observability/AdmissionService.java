package app.weavora.zhijie.controlplane.observability;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.GrantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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

    public void admit(String channel) {
        String kind = channel.equals("VOICE") ? "VOICE" : "NETWORK";
        int limit = kind.equals("VOICE") ? voiceLimit : networkLimit;
        if (grants.countActive(kind) >= limit) {
            String code = kind.equals("VOICE") ? "VOICE_CAPACITY_LIMITED" : "NETWORK_CAPACITY_LIMITED";
            observations.recordApiResult("capacity_rejected");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, "当前通道容量已满，请稍后重试", true);
        }
        observations.recordApiResult("accepted");
    }
}
