package app.weavora.zhijie.controlplane.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.sync.OutboxEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class LiveKitVoiceGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-11-17T00:00:00Z");
    private final String secret = "controlled-test-secret-at-least-32-characters";

    @Test
    void jwtIsSignedAndRestrictedToActivityGenerationAndMicrophone() throws Exception {
        var gateway = gateway("http://127.0.0.1:7880");
        String jwt = gateway.issueToken("activity-a", "user-a", 7L, now.plusSeconds(60));
        String[] parts = jwt.split("\\.");
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertThat(Base64.getUrlDecoder().decode(parts[2])).isEqualTo(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)));
        var claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.path("iss").asText()).isEqualTo("test-api-key");
        assertThat(claims.path("sub").asText()).isEqualTo("user-a:7");
        assertThat(claims.path("exp").asLong()).isEqualTo(now.plusSeconds(60).getEpochSecond());
        var grants = claims.path("video");
        assertThat(grants.path("room").asText()).isEqualTo("activity-a");
        assertThat(grants.path("roomJoin").asBoolean()).isTrue();
        assertThat(grants.path("canPublishData").asBoolean()).isFalse();
        assertThat(grants.has("roomAdmin")).isFalse();
        assertThat(grants.path("canPublishSources").get(0).asText()).isEqualTo("microphone");
        assertThatThrownBy(() -> gateway.issueToken("a", "u", 1, now.plusSeconds(61))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void immediateRetryIssuesDistinctCredentialsForTheSameParticipantAndExpiry() throws Exception {
        var gateway = gateway("http://127.0.0.1:7880");
        Instant deadline = now.plusSeconds(60);
        String first = gateway.issueToken("activity-a", "user-a", 7L, deadline);
        String retry = gateway.issueToken("activity-a", "user-a", 7L, deadline);
        assertThat(first.equals(retry)).as("each issuance must have a distinct token digest").isFalse();
        var original = (ObjectNode) json.readTree(Base64.getUrlDecoder().decode(first.split("\\.")[1]));
        var repeated = (ObjectNode) json.readTree(Base64.getUrlDecoder().decode(retry.split("\\.")[1]));
        assertThat(original.path("jti").asText()).isNotBlank().isNotEqualTo(repeated.path("jti").asText());
        assertThat(repeated.path("jti").asText()).isNotBlank();
        original.remove("jti");
        repeated.remove("jti");
        assertThat(repeated).isEqualTo(original);
    }

    @Test
    void removalTargetsOnlyRevokedGenerationAndRetriesFailures() throws Exception {
        var requestBody = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var status = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/twirp/livekit.RoomService/RemoveParticipant", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = (status.get() == 404 ? "{\"code\":\"not_found\"}" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var gateway = gateway("http://127.0.0.1:" + server.getAddress().getPort());
            var event = new OutboxEvent(1L, "event", "group", "activity-a", "user-a", "voice.revoked",
                    "{\"user_id\":\"user-a\",\"member_generation\":7}", 7L, 1L, now, null, 0);
            gateway.deliver(event);
            assertThat(json.readTree(requestBody.get()).path("identity").asText()).isEqualTo("user-a:7");
            assertThat(json.readTree(requestBody.get()).path("room").asText()).isEqualTo("activity-a");
            String adminToken = authorization.get().substring(7);
            var grants = json.readTree(Base64.getUrlDecoder().decode(adminToken.split("\\.")[1])).path("video");
            assertThat(grants.path("roomAdmin").asBoolean()).isTrue();
            assertThat(grants.path("room").asText()).isEqualTo("activity-a");
            status.set(404); gateway.deliver(event);
            status.set(500);
            assertThatThrownBy(() -> gateway.deliver(event)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo("VOICE_REVOCATION_FAILED"));
        } finally { server.stop(0); }
    }

    @Test
    void remoteManagementRequiresTlsAndConfiguredSecret() {
        assertThatThrownBy(() -> gateway("http://sfu.example.test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveKitVoiceGateway("key", "short", "https://sfu.example.test", json, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(gateway("https://sfu.example.test").isAvailable()).isTrue();
    }

    private LiveKitVoiceGateway gateway(String url) {
        return new LiveKitVoiceGateway("test-api-key", secret, url, json, Clock.fixed(now, ZoneOffset.UTC));
    }
}
