package app.weavora.zhijie.controlplane.grant;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.revocation.RevocationTarget;
import app.weavora.zhijie.controlplane.sync.OutboxEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** LiveKit 标准 HS256 授权和 RoomService 撤销；管理密钥仅保留在控制面。 */
@Component
@ConditionalOnProperty(name = "zhijie.voice.enabled", havingValue = "true")
public class LiveKitVoiceGateway implements VoiceGateway, RevocationTarget {
    private final String apiKey;
    private final byte[] apiSecret;
    private final URI adminUrl;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public LiveKitVoiceGateway(@Value("${zhijie.voice.api-key}") String apiKey,
                              @Value("${zhijie.voice.api-secret}") String apiSecret,
                              @Value("${zhijie.voice.admin-url:http://127.0.0.1:7880}") String adminUrl,
                              ObjectMapper json, Clock clock) {
        this.adminUrl = URI.create(adminUrl);
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.length() < 32) {
            throw new IllegalArgumentException("语音服务须配置 API Key 和至少 32 字符的 API Secret");
        }
        String scheme = this.adminUrl.getScheme();
        String host = this.adminUrl.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host);
        if (host == null || this.adminUrl.getUserInfo() != null
                || this.adminUrl.getQuery() != null || this.adminUrl.getFragment() != null
                || !("https".equals(scheme) || ("http".equals(scheme) && loopback))) {
            throw new IllegalArgumentException("语音管理地址必须使用 HTTPS；仅回环地址允许 HTTP");
        }
        this.apiKey = apiKey;
        this.apiSecret = apiSecret.getBytes(StandardCharsets.UTF_8);
        this.json = json;
        this.clock = clock;
    }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public String issueToken(String activityId, String userId, long memberGeneration, Instant expiresAt) {
        if (!expiresAt.isAfter(clock.instant()) || expiresAt.isAfter(clock.instant().plusSeconds(60))) {
            throw new IllegalArgumentException("语音凭据有效期必须在 60 秒内");
        }
        return token(identity(userId, memberGeneration), expiresAt, Map.of(
                "roomJoin", true, "room", activityId, "canPublish", true, "canSubscribe", true,
                "canPublishData", false, "canPublishSources", List.of("microphone")));
    }

    public static String identity(String userId, long generation) { return userId + ":" + generation; }

    private String token(String subject, Instant expiresAt, Map<String, Object> permissions) {
        try {
            var encoder = Base64.getUrlEncoder().withoutPadding();
            String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = encoder.encodeToString(json.writeValueAsBytes(Map.of(
                    "iss", apiKey, "sub", subject, "jti", UUID.randomUUID().toString(), "iat", clock.instant().getEpochSecond(),
                    "nbf", clock.instant().minusSeconds(5).getEpochSecond(),
                    "exp", expiresAt.getEpochSecond(), "video", permissions)));
            String signingInput = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret, "HmacSHA256"));
            return signingInput + "." + encoder.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException("语音凭据签发失败");
        }
    }

    @Override
    public boolean supports(String eventType) { return "voice.revoked".equals(eventType); }

    @Override
    public void deliver(OutboxEvent event) {
        try {
            var payload = json.readTree(event.payload());
            String identity = identity(payload.required("user_id").asText(), payload.required("member_generation").asLong());
            String authorization = token("control-plane", clock.instant().plusSeconds(30),
                    Map.of("roomAdmin", true, "room", event.activityId()));
            HttpRequest request = HttpRequest.newBuilder(adminUrl.resolve("/twirp/livekit.RoomService/RemoveParticipant"))
                    .timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer " + authorization)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(
                            Map.of("room", event.activityId(), "identity", identity)))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            // 重复投递时参与者已移除，视为幂等成功；其他失败交给 Outbox 重试。
            if (response.statusCode() == 200) return;
            if (response.statusCode() == 404 && "not_found".equals(json.readTree(response.body()).path("code").asText())) return;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            // 不保留包含 Authorization 的请求或第三方响应异常。
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VOICE_REVOCATION_FAILED", "语音撤销暂未完成", true);
    }
}
