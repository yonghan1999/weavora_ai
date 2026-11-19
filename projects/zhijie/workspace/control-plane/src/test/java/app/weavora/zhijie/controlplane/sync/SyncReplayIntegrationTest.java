package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.message.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
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

class SyncReplayIntegrationTest {
    @Test void replayCrossesUnauthorizedPagesThenContinuesLiveWithoutDuplicateOrLeak() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var credentials = context.getBean(CredentialProtector.class);
            var identities = context.getBean(IdentityRepository.class);
            var groups = context.getBean(GroupService.class);
            var messages = context.getBean(MessageService.class);
            var outbox = context.getBean(OutboxRepository.class);
            var sync = context.getBean(SyncService.class);
            var json = context.getBean(ObjectMapper.class);
            String hash = credentials.hashPassword("sync-replay-password-123");
            String user = UUID.randomUUID().toString(), outsider = UUID.randomUUID().toString();
            identities.insertUser(new UserAccount(user, "replay@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            identities.insertUser(new UserAccount(outsider, "other@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            String token = context.getBean(IdentityService.class).login("replay@example.test", "sync-replay-password-123", "replay-device").accessToken();
            var own = groups.createGroup(user, "接收群", null, "");
            var other = groups.createGroup(outsider, "隔离群", null, "");
            long cursor = outbox.latestSequence();
            for (int index = 0; index < 120; index++) messages.post(outsider, other.id(), "hidden-" + index);
            var expected = new ArrayList<String>();
            for (int index = 0; index < 135; index++) expected.add(messages.post(user, own.id(), "visible-" + index).id());
            var listener = new Collector(json);
            String entry = "ws://127.0.0.1:" + ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(
                    URI.create(entry + "/api/v1/ws?access_token=" + token + "&cursor=" + cursor), listener).get(5, TimeUnit.SECONDS);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (listener.events.size() < 135 && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(listener.events).as("all 135 authorized events beyond the hidden first page").hasSize(135);
                assertThat(listener.events).allMatch(event -> event.path("groupId").asText().equals(own.id()));
                assertThat(listener.messageIds()).containsExactlyElementsOf(expected);
                // 重复投递历史 outbox 不得在同一连接回放第二次。
                for (var event : outbox.listAfter(cursor, 300)) sync.broadcast(event);
                String live = messages.post(user, own.id(), "live-after-replay").id();
                var event = outbox.listAfter(outbox.latestSequence() - 1, 1).getFirst();
                sync.broadcast(event);
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!listener.messageIds().contains(live) && System.nanoTime() < deadline) Thread.sleep(10);
                Thread.sleep(100);
                expected.add(live);
                assertThat(listener.messageIds()).containsExactlyElementsOf(expected);
                assertThat(listener.events.stream().map(value -> value.path("sequence").asLong()).toList()).isSorted().doesNotHaveDuplicates();
                assertThat(listener.failure).isNull();
            } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS); }
        }
    }

    private static class Collector implements WebSocket.Listener {
        private final ObjectMapper json;
        private final StringBuilder partial = new StringBuilder();
        final List<JsonNode> events = new CopyOnWriteArrayList<>();
        volatile Throwable failure;
        Collector(ObjectMapper json) { this.json = json; }
        public void onOpen(WebSocket socket) { socket.request(1); }
        public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            partial.append(text);
            if (last) {
                try { events.add(json.readTree(partial.toString())); } catch (Exception error) { failure = error; }
                partial.setLength(0);
            }
            socket.request(1); return null;
        }
        public void onError(WebSocket socket, Throwable error) { failure = error; }
        List<String> messageIds() {
            return events.stream().map(value -> {
                try { return json.readTree(value.path("payload").asText()).path("message_id").asText(); }
                catch (Exception error) { throw new IllegalStateException("Invalid test sync payload", error); }
            }).toList();
        }
    }
}
