package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.message.MessageService;
import app.weavora.zhijie.controlplane.revocation.RevocationDispatcher;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncOutboxIsolationIntegrationTest {
    @Test void pendingExternalRevocationsDoNotStarveLiveSyncOrRepeatItsCompletedDelivery() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identity = context.getBean(IdentityService.class);
            String user = UUID.randomUUID().toString();
            context.getBean(IdentityRepository.class).insertUser(new UserAccount(user, "outbox-isolation@example.test",
                    AccountStatus.ACTIVE, context.getBean(CredentialProtector.class).hashPassword("outbox-isolation-password"), Instant.now()));
            var token = identity.login("outbox-isolation@example.test", "outbox-isolation-password", "device");
            var group = context.getBean(GroupService.class).createGroup(user, "投递隔离", null, "");
            var dispatcher = context.getBean(RevocationDispatcher.class);
            dispatcher.dispatchPending();
            var outbox = context.getBean(OutboxService.class);
            for (int index = 0; index < 110; index++) {
                outbox.record(null, "old-activity", "backlog-" + index, "network.revoked",
                        "{\"user_id\":\"departed-user\"}", 1L, 1L);
            }
            long cursor = context.getBean(OutboxRepository.class).latestSequence();
            var listener = new Listener();
            int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            try (var http = HttpClient.newHttpClient()) {
                var socket = http.newWebSocketBuilder().header("Authorization", "Bearer " + token.accessToken())
                        .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/ws?cursor=" + cursor), listener).get(5, TimeUnit.SECONDS);
                try {
                    String message = context.getBean(MessageService.class).post(user, group.id(), "积压之后的消息").id();
                    for (int round = 0; round < 3; round++) dispatcher.dispatchPending();
                    listener.message.get(3, TimeUnit.SECONDS);
                    assertThat(listener.events).anyMatch(event -> event.contains(message));
                    var sql = context.getBean(JdbcTemplate.class);
                    assertThat(sql.queryForObject("SELECT count(*) FROM outbox_events WHERE type='network.revoked' AND delivered_at IS NULL", Integer.class)).isEqualTo(110);
                    int count = listener.events.size();
                    dispatcher.dispatchPending();
                    socket.sendPing(ByteBuffer.wrap(new byte[] {1})).get(3, TimeUnit.SECONDS);
                    listener.pong.get(3, TimeUnit.SECONDS);
                    assertThat(listener.closed.isDone()).isFalse();
                    assertThat(listener.events).hasSize(count);
                } finally { socket.abort(); }
            }
        }
    }

    @Test void externalRetriesDoNotCloseTheRevokedUsersReplacementSocket() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            String user = UUID.randomUUID().toString();
            context.getBean(IdentityRepository.class).insertUser(new UserAccount(user, "outbox-reconnect@example.test",
                    AccountStatus.ACTIVE, context.getBean(CredentialProtector.class).hashPassword("outbox-reconnect-password"), Instant.now()));
            var token = context.getBean(IdentityService.class).login("outbox-reconnect@example.test", "outbox-reconnect-password", "device");
            var group = context.getBean(GroupService.class).createGroup(user, "撤销后重新连接", null, "");
            var dispatcher = context.getBean(RevocationDispatcher.class);
            dispatcher.dispatchPending();
            var repository = context.getBean(OutboxRepository.class);
            int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            String entry = "ws://127.0.0.1:" + port + "/api/v1/ws?cursor=";
            var original = new Listener();
            try (var http = HttpClient.newHttpClient()) {
                var socket = http.newWebSocketBuilder().header("Authorization", "Bearer " + token.accessToken())
                        .buildAsync(URI.create(entry + repository.latestSequence()), original).get(5, TimeUnit.SECONDS);
                try {
                    var revocation = context.getBean(OutboxService.class).record(null, "old-activity", user, "network.revoked",
                            "{\"user_id\":\"" + user + "\"}", 1L, 1L);
                    assertThat(dispatcher.dispatchPending().failed()).isEqualTo(1);
                    assertThat(original.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1008);
                    var replacement = new Listener();
                    var reconnected = http.newWebSocketBuilder().header("Authorization", "Bearer " + token.accessToken())
                            .buildAsync(URI.create(entry + repository.latestSequence()), replacement).get(5, TimeUnit.SECONDS);
                    try {
                        assertThat(dispatcher.dispatchPending().failed()).isEqualTo(1);
                        String message = context.getBean(MessageService.class).post(user, group.id(), "外部重试后的消息").id();
                        dispatcher.dispatchPending();
                        replacement.message.get(3, TimeUnit.SECONDS);
                        assertThat(replacement.events).anyMatch(event -> event.contains(message));
                        assertThat(replacement.closed.isDone()).isFalse();
                        assertThat(repository.listUnsynchronized(100)).isEmpty();
                        assertThat(repository.listPendingExternal(100)).extracting(OutboxEvent::eventId).containsExactly(revocation.eventId());
                    } finally { reconnected.abort(); }
                } finally { socket.abort(); }
            }
        }
    }

    @Test void migrationPreservesCompletedEventsAndPendingSyncSurvivesRestart() throws Exception {
        try (var postgres = EmbeddedPostgres.start()) {
            String url = postgres.getJdbcUrl("postgres", "postgres");
            Flyway.configure().dataSource(url, "postgres", "").target("14").load().migrate();
            try (var connection = DriverManager.getConnection(url, "postgres", ""); var statement = connection.createStatement()) {
                statement.execute("INSERT INTO outbox_events (sequence,event_id,aggregate_id,type,payload,occurred_at,delivered_at,attempts) "
                        + "VALUES (3,'completed','user','test.changed','{}',NOW(),'2026-01-01T00:00:00Z',0),"
                        + "(9,'pending','user','network.revoked','{\"user_id\":\"departed-user\"}',NOW(),NULL,2)");
                statement.execute("INSERT INTO sync_event_sequences (sequence,event_id) VALUES (3,'completed'),(9,'pending'); "
                        + "SELECT setval('sync_event_sequences_sequence_seq',9)");
            }
            Flyway.configure().dataSource(url, "postgres", "").load().migrate();
            Instant synchronizedAt;
            try (var context = new SpringApplication(ControlPlaneApplication.class).run(
                    "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                    "--spring.datasource.url=" + url, "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
                var repository = context.getBean(OutboxRepository.class);
                var sql = context.getBean(JdbcTemplate.class);
                assertThat(sql.queryForObject("SELECT sync_delivered_at=delivered_at FROM outbox_events WHERE event_id='completed'", Boolean.class)).isTrue();
                assertThat(repository.listUnsynchronized(100)).extracting(OutboxEvent::eventId).containsExactly("pending");
                assertThat(repository.listPendingExternal(100)).isEmpty();
                assertThat(repository.listAfter(0, 100)).extracting(OutboxEvent::sequence).containsExactly(3L, 9L);
                assertThat(context.getBean(RevocationDispatcher.class).dispatchPending().failed()).isEqualTo(1);
                assertThat(repository.listUnsynchronized(100)).isEmpty();
                synchronizedAt = sql.queryForObject("SELECT sync_delivered_at FROM outbox_events WHERE event_id='pending'", java.sql.Timestamp.class).toInstant();
                assertThat(repository.listPendingExternal(100)).extracting(OutboxEvent::attempts).containsExactly(3);
            }
            try (var context = new SpringApplication(ControlPlaneApplication.class).run(
                    "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                    "--spring.datasource.url=" + url, "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
                var repository = context.getBean(OutboxRepository.class);
                assertThat(repository.listUnsynchronized(100)).isEmpty();
                assertThat(context.getBean(RevocationDispatcher.class).dispatchPending().failed()).isEqualTo(1);
                assertThat(repository.listPendingExternal(100)).extracting(OutboxEvent::attempts).containsExactly(4);
                assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                        "SELECT sync_delivered_at FROM outbox_events WHERE event_id='pending'", java.sql.Timestamp.class).toInstant()).isEqualTo(synchronizedAt);
                assertThat(context.getBean(SyncService.class).history("test-user", 3)).extracting(SyncEvent::eventId).containsExactly("pending");
                String fresh = context.getBean(OutboxService.class).record(null, null, "new", "test.changed", "{}", null, null).eventId();
                assertThat(repository.listAfter(9, 100)).extracting(OutboxEvent::eventId).containsExactly(fresh);
                assertThat(repository.listAfter(9, 100).getFirst().sequence()).isGreaterThan(9);
            }
        }
    }

    private static class Listener implements WebSocket.Listener {
        final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> message = new CompletableFuture<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final CompletableFuture<Void> pong = new CompletableFuture<>();
        final StringBuilder partial = new StringBuilder();
        public void onOpen(WebSocket socket) { socket.request(1); }
        public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            partial.append(text);
            if (last) {
                String event = partial.toString();
                events.add(event);
                partial.setLength(0);
                if (event.contains("message.created")) message.complete(null);
            }
            socket.request(1);
            return null;
        }
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) { closed.complete(status); return null; }
        public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) { pong.complete(null); socket.request(1); return null; }
        public void onError(WebSocket socket, Throwable failure) { message.completeExceptionally(new IllegalStateException("SYNC_FAILED")); }
    }
}
