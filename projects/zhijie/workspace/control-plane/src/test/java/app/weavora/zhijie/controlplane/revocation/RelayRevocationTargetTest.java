package app.weavora.zhijie.controlplane.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.sync.OutboxEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RelayRevocationTargetTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String key = "revocation-key-12345678901234567890";

    @Test void invalidOrSharedManagementConfigurationFailsWithoutIncludingCredentials() {
        for (String url : java.util.List.of("", "http://remote.example.test", "https://user:secret@example.test",
                "http://127.0.0.1:0", "https://example.test/path", "https://example.test?key=secret", "https://example.test/#secret")) {
            assertThatThrownBy(() -> new RelayRevocationTarget(url, key, "different-validation-key", json))
                    .hasMessage("RELAY_REVOCATION_CONFIGURATION_INVALID");
        }
        for (String invalid : java.util.List.of("short", key + "\n", key + " 中文", " ".repeat(32), "a".repeat(257))) {
            assertThatThrownBy(() -> new RelayRevocationTarget("http://127.0.0.1:1234", invalid, "other", json))
                    .hasMessage("RELAY_REVOCATION_CONFIGURATION_INVALID");
        }
        assertThatThrownBy(() -> new RelayRevocationTarget("http://127.0.0.1:1234", key, key, json))
                .hasMessage("RELAY_REVOCATION_CONFIGURATION_INVALID");
    }

    @Test void onlyMatchingBoundedAcknowledgementCompletesAndRedirectNeverReceivesKey() throws Exception {
        var status = new AtomicInteger(200);
        var attempts = new AtomicInteger();
        var redirectAttempts = new AtomicInteger();
        var body = new java.util.concurrent.atomic.AtomicReference<>("{}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/relay/v1/revocations", exchange -> {
            attempts.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("X-Relay-Revocation-Key")).isEqualTo(key);
            if (status.get() == 302) exchange.getResponseHeaders().set("Location", "/redirect");
            byte[] bytes = body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> { redirectAttempts.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            var target = new RelayRevocationTarget("http://127.0.0.1:" + server.getAddress().getPort(), key, "other", json);
            var event = event("{\"user_id\":\"user\",\"member_generation\":1}", 1L);
            assertThat(target.supports("network.revoked")).isTrue();
            assertThat(target.supports("voice.revoked")).isFalse();
            for (String invalid : java.util.List.of("{}", "{\"version\":1,\"eventId\":\"other\",\"status\":\"applied\"}",
                    "{\"version\":2,\"eventId\":\"event\",\"status\":\"applied\"}", "{\"version\":1,\"eventId\":\"event\",\"status\":\"queued\"}",
                    "{\"version\":\"1\",\"eventId\":\"event\",\"status\":\"applied\"}",
                    "{\"version\":1.5,\"eventId\":\"event\",\"status\":\"applied\"}",
                    "{\"version\":4294967297,\"eventId\":\"event\",\"status\":\"applied\"}",
                    "x".repeat(4097))) {
                body.set(invalid);
                assertFailed(target, event);
            }
            body.set("{\"version\":1,\"eventId\":\"event\",\"status\":\"applied\"}");
            for (int failure : new int[] {302, 403, 404, 500}) { status.set(failure); assertFailed(target, event); }
            assertThat(redirectAttempts.get()).isZero();
            int before = attempts.get();
            assertFailed(target, event("{\"user_id\":\"user\",\"member_generation\":2}", 1L));
            assertFailed(target, event("{\"user_id\":\"\",\"member_generation\":1}", 1L));
            for (String invalidGeneration : java.util.List.of("\"1\"", "1.5", "null", "true", "9223372036854775808")) {
                assertFailed(target, event("{\"user_id\":\"user\",\"member_generation\":" + invalidGeneration + "}", 1L));
            }
            assertFailed(target, event("{\"user_id\":123,\"member_generation\":1}", 1L));
            assertThat(attempts.get()).isEqualTo(before);
            status.set(200);
            target.deliver(event);
            target.deliver(event);
            assertThat(attempts.get()).isEqualTo(before + 2);
        } finally { server.stop(0); }
    }

    private void assertFailed(RelayRevocationTarget target, OutboxEvent event) {
        assertThatThrownBy(() -> target.deliver(event)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("RELAY_REVOCATION_FAILED");
    }

    private OutboxEvent event(String payload, Long generation) {
        return new OutboxEvent(1L, "event", "group", "activity", "user", "network.revoked", payload,
                generation, 1L, Instant.now(), null, 0);
    }
}
