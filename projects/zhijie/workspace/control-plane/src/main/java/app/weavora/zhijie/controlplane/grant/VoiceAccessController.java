package app.weavora.zhijie.controlplane.grant;

import app.weavora.zhijie.controlplane.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VoiceAccessController {
    private final VoiceAccessValidator validator;
    private final byte[] managementKey;

    public VoiceAccessController(VoiceAccessValidator validator,
                                 @Value("${zhijie.voice.signaling-key:}") String managementKey) {
        this.validator = validator;
        this.managementKey = managementKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/voice/v1/authorize")
    public ResponseEntity<Void> authorize(@RequestHeader(value = "X-Voice-Key", defaultValue = "") String key,
                                          @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
                                          @RequestHeader(value = "X-Voice-Token", defaultValue = "") String queryToken) {
        if (managementKey.length < 32 || !MessageDigest.isEqual(managementKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "VOICE_ACCESS_DENIED", "语音信令管理通道未授权", false);
        }
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : queryToken;
        validator.validate(token);
        return ResponseEntity.noContent().build();
    }
}
