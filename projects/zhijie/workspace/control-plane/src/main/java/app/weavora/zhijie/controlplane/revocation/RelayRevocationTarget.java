package app.weavora.zhijie.controlplane.revocation;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.sync.OutboxEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "zhijie.relay.revocation-enabled", havingValue = "true")
public class RelayRevocationTarget implements RevocationTarget {
    private final URI endpoint;
    private final String key;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public RelayRevocationTarget(@Value("${zhijie.relay.admin-url:}") String adminUrl,
                                 @Value("${zhijie.relay.revocation-key:}") String key,
                                 @Value("${zhijie.relay.management-key:}") String validationKey,
                                 ObjectMapper json) {
        URI base;
        try { base = URI.create(adminUrl); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("RELAY_REVOCATION_CONFIGURATION_INVALID"); }
        String host = base.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host);
        if (host == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))
                || base.getPort() == 0 || base.getPort() > 65535
                || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()) && loopback)
                || key == null || key.length() < 32 || key.length() > 256 || key.equals(validationKey)
                || !key.chars().allMatch(character -> character >= 33 && character <= 126)) {
            throw new IllegalArgumentException("RELAY_REVOCATION_CONFIGURATION_INVALID");
        }
        this.endpoint = base.resolve("/internal/relay/v1/revocations");
        this.key = key;
        this.json = json;
    }

    public boolean supports(String type) { return "network.revoked".equals(type); }

    public void deliver(OutboxEvent event) {
        try {
            var payload = json.readTree(event.payload());
            var userNode = payload.required("user_id");
            var generationNode = payload.required("member_generation");
            if (!userNode.isTextual() || !generationNode.isIntegralNumber() || !generationNode.canConvertToLong()) {
                throw new IllegalArgumentException("RELAY_REVOCATION_EVENT_INVALID");
            }
            String user = userNode.textValue();
            long generation = generationNode.longValue();
            if (event.activityId() == null || event.activityId().isBlank() || user.isBlank()
                    || event.generation() == null || generation < 1 || generation != event.generation()) {
                throw new IllegalArgumentException("RELAY_REVOCATION_EVENT_INVALID");
            }
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json").header("X-Relay-Revocation-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of(
                            "version", 1, "eventId", event.eventId(), "activityId", event.activityId(),
                            "userId", user, "memberGeneration", generation)))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), 4096));
            if (response.statusCode() == 200) {
                var acknowledgement = json.readTree(response.body());
                var version = acknowledgement.path("version");
                if (version.isIntegralNumber() && version.canConvertToInt() && version.intValue() == 1
                        && event.eventId().equals(acknowledgement.path("eventId").textValue())
                        && "applied".equals(acknowledgement.path("status").textValue())) return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failed) {
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RELAY_REVOCATION_FAILED", "中继撤销暂未完成", true);
    }
}
