package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.message.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

class SyncSessionIntegrationTest {
    @Test void logoutRotationAndExpiryCloseOnlyTheInvalidSocketEvenWhenIdle() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identities = context.getBean(IdentityRepository.class);
            var identity = context.getBean(IdentityService.class);
            var groups = context.getBean(GroupService.class);
            var messages = context.getBean(MessageService.class);
            var outbox = context.getBean(OutboxRepository.class);
            var json = context.getBean(ObjectMapper.class);
            String user = UUID.randomUUID().toString();
            identities.insertUser(new UserAccount(user, "sync-session@example.test", AccountStatus.ACTIVE,
                    context.getBean(CredentialProtector.class).hashPassword("sync-session-password-123"), Instant.now()));
            var group = groups.createGroup(user, "会话边界", null, "");
            String entry = "ws://127.0.0.1:" + ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var http = HttpClient.newHttpClient();
            var healthyToken = identity.login("sync-session@example.test", "sync-session-password-123", "healthy");
            var healthy = new Listener();
            var healthySocket = open(http, entry, healthyToken.accessToken(), outbox.latestSequence(), healthy);
            try {
                for (var action : List.of("logout", "rotate", "expire")) {
                    var token = identity.login("sync-session@example.test", "sync-session-password-123", action);
                    var invalid = new Listener();
                    var socket = open(http, entry, token.accessToken(), outbox.latestSequence(), invalid);
                    try {
                        String baseline = messages.post(user, group.id(), "baseline-" + action).id();
                        awaitMessage(invalid, json, baseline);
                        AuthTokens rotated = null;
                        if (action.equals("logout")) identity.logout(token.accessToken());
                        if (action.equals("rotate")) rotated = identity.refresh(token.refreshToken());
                        if (action.equals("expire")) {
                            var current = identities.findSessionById(token.sessionId()).orElseThrow();
                            identities.updateSession(new AuthSession(current.id(), current.userId(), current.deviceId(),
                                    current.refreshTokenHash(), current.accessTokenHash(), current.expiresAt(),
                                    Instant.now().minusSeconds(1), null, current.generation()));
                        }
                        // 不生成业务事件，仍须在五秒内关闭失效连接。
                        assertThat(invalid.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
                        int before = invalid.events.size();
                        String after = messages.post(user, group.id(), "after-" + action).id();
                        awaitMessage(healthy, json, after);
                        assertThat(invalid.events).hasSize(before);
                        assertThat(healthy.closed.isDone()).isFalse();
                        if (rotated != null) {
                            var replacement = new Listener();
                            var replacementSocket = open(http, entry, rotated.accessToken(), outbox.latestSequence(), replacement);
                            try {
                                String next = messages.post(user, group.id(), "replacement").id();
                                awaitMessage(replacement, json, next);
                            } finally { replacementSocket.sendClose(1000, "done").get(3, TimeUnit.SECONDS); }
                        }
                    } finally { socket.abort(); }
                }
            } finally { healthySocket.sendClose(1000, "done").get(3, TimeUnit.SECONDS); }
        }
    }

    private static WebSocket open(HttpClient http, String entry, String token, long cursor, Listener listener) throws Exception {
        return http.newWebSocketBuilder().header("Authorization", "Bearer " + token)
                .buildAsync(URI.create(entry + "/api/v1/ws?cursor=" + cursor), listener).get(5, TimeUnit.SECONDS);
    }

    private static void awaitMessage(Listener listener, ObjectMapper json, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            for (String event : listener.events) {
                var node = json.readTree(event);
                if (node.path("type").asText().equals("message.created") && json.readTree(node.path("payload").asText()).path("message_id").asText().equals(id)) return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected actual sync message within deadline");
    }

    private static class Listener implements WebSocket.Listener {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder partial = new StringBuilder();
        public void onOpen(WebSocket socket) { socket.request(1); }
        public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            partial.append(text);
            if (last) { events.add(partial.toString()); partial.setLength(0); }
            socket.request(1); return null;
        }
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) { closed.complete(status); return null; }
        public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(new IllegalStateException("Sync transport failed")); }
    }
}
