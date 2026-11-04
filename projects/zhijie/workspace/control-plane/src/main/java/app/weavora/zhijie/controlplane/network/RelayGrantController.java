package app.weavora.zhijie.controlplane.network;

import app.weavora.zhijie.controlplane.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/relay/v1")
public class RelayGrantController {
    private final RelayGrantValidationService validation;
    private final String managementKey;

    public RelayGrantController(RelayGrantValidationService validation,
                                @Value("${zhijie.relay.management-key:}") String managementKey) {
        this.validation = validation;
        this.managementKey = managementKey;
    }

    @PostMapping("/validate")
    public RelayValidationResponse validate(@RequestHeader("X-Relay-Key") String key,
                                            @Valid @RequestBody RelayRequest request) {
        if (managementKey.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RELAY_MANAGEMENT_UNAVAILABLE", "Relay 管理通道未配置", false);
        }
        if (!MessageDigest.isEqual(managementKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "Relay 管理凭据无效", false);
        }
        return validation.validate(request.ticket());
    }

    public record RelayRequest(@NotBlank String ticket, @NotBlank String clientEndpoint) {
    }
}
