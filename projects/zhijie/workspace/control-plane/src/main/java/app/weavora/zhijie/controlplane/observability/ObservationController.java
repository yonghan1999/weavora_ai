package app.weavora.zhijie.controlplane.observability;

import app.weavora.zhijie.controlplane.common.ApiException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ObservationController {
    private final ObservationService observations;
    private final String operationsToken;

    public ObservationController(ObservationService observations, @Value("${zhijie.ops.token:}") String operationsToken) {
        this.observations = observations;
        this.operationsToken = operationsToken;
    }

    @GetMapping("/internal/observability/summary")
    public Map<String, Object> summary(@RequestHeader("X-Ops-Token") String token) {
        if (operationsToken.isEmpty() || !operationsToken.equals(token)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "运营凭据无效", false);
        }
        return Map.of("data", observations.summary());
    }
}
