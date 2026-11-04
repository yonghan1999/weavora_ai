package app.weavora.zhijie.controlplane.grant;

import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class UnavailableVoiceGateway implements VoiceGateway {
    public boolean isAvailable() { return false; }
    public String issueToken(String activityId, String userId, long memberGeneration, Instant expiresAt) {
        throw new IllegalStateException("Voice gateway is not configured");
    }
}
