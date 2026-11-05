package app.weavora.zhijie.controlplane.network;

import app.weavora.zhijie.controlplane.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ops/network-policy")
public class NetworkPolicyController {
    private final NetworkPolicyService policies;
    private final String operationsToken;

    public NetworkPolicyController(NetworkPolicyService policies,
                                   @Value("${zhijie.ops.token:}") String operationsToken) {
        this.policies = policies;
        this.operationsToken = operationsToken;
    }

    @GetMapping
    public Map<String, NetworkPolicyRevision> current(@RequestHeader("X-Ops-Token") String token) {
        authorize(token);
        return Map.of("data", policies.current());
    }

    @PutMapping
    public Map<String, NetworkPolicyRevision> update(@RequestHeader("X-Ops-Token") String token,
                                                     @Valid @RequestBody PolicyBody body) {
        authorize(token);
        return Map.of("data", policies.update("operations", body.mode(), body.expectedRevision()));
    }

    @PostMapping("/{revision}/rollback")
    public Map<String, NetworkPolicyRevision> rollback(@RequestHeader("X-Ops-Token") String token,
                                                       @PathVariable long revision,
                                                       @RequestBody ExpectedRevisionBody body) {
        authorize(token);
        return Map.of("data", policies.rollback("operations", revision, body.expectedRevision()));
    }

    private void authorize(String token) {
        if (operationsToken.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OPERATIONS_CHANNEL_UNAVAILABLE", "运营通道未配置", false);
        }
        if (!MessageDigest.isEqual(operationsToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "运营凭据无效", false);
        }
    }

    public record PolicyBody(@NotNull NetworkPolicyMode mode, long expectedRevision) {
    }
    public record ExpectedRevisionBody(long expectedRevision) {
    }
}
