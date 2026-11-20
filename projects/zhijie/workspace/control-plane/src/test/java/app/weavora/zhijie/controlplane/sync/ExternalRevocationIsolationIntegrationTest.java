package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.message.MessageService;
import app.weavora.zhijie.controlplane.revocation.RevocationDispatcher;
import app.weavora.zhijie.controlplane.revocation.RevocationTarget;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.sql.DriverManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;

class ExternalRevocationIsolationIntegrationTest {
    @Test void slowExternalDeliveryDoesNotBlockScheduledSyncOrIdleSessionInvalidation() throws Exception {
        var target = new SlowTarget();
        var application = new SpringApplication(ControlPlaneApplication.class);
        application.addInitializers(context -> context.getBeanFactory().registerSingleton("slowRevocationTarget", target));
        try (var postgres = EmbeddedPostgres.start(); var context = application.run(
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            try {
                var credentials = context.getBean(CredentialProtector.class);
                var identity = context.getBean(IdentityService.class);
                String user = "slow-external-user";
                context.getBean(IdentityRepository.class).insertUser(new UserAccount(user, "slow-external@example.test",
                        AccountStatus.ACTIVE, credentials.hashPassword("slow-external-password"), Instant.now()));
                var tokens = identity.login("slow-external@example.test", "slow-external-password", "device");
                var idleTokens = identity.login("slow-external@example.test", "slow-external-password", "idle-device");
                var group = context.getBean(GroupService.class).createGroup(user, "外部慢服务隔离", null, "");
                int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
                String entry = "ws://127.0.0.1:" + port + "/api/v1/ws?cursor=" + context.getBean(OutboxRepository.class).latestSequence();
                var listener = new Listener();
                var idle = new Listener();
                try (var http = HttpClient.newHttpClient()) {
                    var socket = http.newWebSocketBuilder().header("Authorization", "Bearer " + tokens.accessToken())
                            .buildAsync(URI.create(entry), listener).get(5, TimeUnit.SECONDS);
                    var idleSocket = http.newWebSocketBuilder().header("Authorization", "Bearer " + idleTokens.accessToken())
                            .buildAsync(URI.create(entry), idle).get(5, TimeUnit.SECONDS);
                    try {
                        context.getBean(OutboxService.class).record(null, null, "slow-target", "test.slow", "{}", null, null);
                        assertThat(target.entered.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(context.getBean(org.springframework.jdbc.core.JdbcTemplate.class).queryForObject(
                                "SELECT sync_delivered_at IS NOT NULL AND delivered_at IS NULL FROM outbox_events WHERE type='test.slow'", Boolean.class)).isTrue();
                        String message = context.getBean(MessageService.class).post(user, group.id(), "外部仍阻塞时的消息").id();
                        assertThat(listener.message.get(3, TimeUnit.SECONDS)).contains(message);
                        identity.logout(idleTokens.accessToken());
                        assertThat(idle.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1008);
                        assertThat(target.release.getCount()).isEqualTo(1);
                        assertThat(listener.closed.isDone()).isFalse();
                    } finally { socket.abort(); idleSocket.abort(); }
                }
            } finally { target.release.countDown(); }
        }
    }

    @Test void externalRetryBackoffIsBoundedAndPersistsAcrossRestartWithoutChangingSyncHistory() throws Exception {
        try (var postgres = EmbeddedPostgres.start()) {
            String url = postgres.getJdbcUrl("postgres", "postgres");
            Flyway.configure().dataSource(url, "postgres", "").target("15").load().migrate();
            try (var connection = DriverManager.getConnection(url, "postgres", ""); var statement = connection.createStatement()) {
                statement.execute("INSERT INTO outbox_events (sequence,event_id,aggregate_id,type,payload,occurred_at,delivered_at,sync_delivered_at,attempts) "
                        + "VALUES (3,'completed','user','test.ready','{}',NOW(),'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z',0),"
                        + "(9,'pending','user','network.revoked','{\"user_id\":\"departed-user\"}',NOW(),NULL,'2026-01-01T00:00:00Z',20)");
                statement.execute("INSERT INTO sync_event_sequences (sequence,event_id) VALUES (3,'completed'),(9,'pending'); "
                        + "SELECT setval('sync_event_sequences_sequence_seq',9)");
            }
            Instant now = Instant.parse("2026-01-01T01:00:00Z");
            Instant next;
            try (var context = new SpringApplication(ControlPlaneApplication.class).run(
                    "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                    "--spring.datasource.url=" + url, "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
                var repository = context.getBean(OutboxRepository.class);
                var sql = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
                assertThat(repository.listDueExternal(100, now)).extracting(OutboxEvent::eventId).containsExactly("pending");
                assertThat(sql.queryForObject("SELECT external_attempts FROM outbox_events WHERE event_id='pending'", Integer.class)).isZero();
                int attempt = 0;
                for (int seconds : new int[] {2, 4, 8, 16, 32, 60, 60, 60}) {
                    repository.recordExternalFailure("pending", now);
                    next = now.plusSeconds(seconds);
                    assertThat(sql.queryForObject("SELECT external_next_attempt_at FROM outbox_events WHERE event_id='pending'", java.sql.Timestamp.class).toInstant()).isEqualTo(next);
                    assertThat(repository.listDueExternal(100, next.minusNanos(1000))).isEmpty();
                    assertThat(repository.listDueExternal(100, next)).extracting(OutboxEvent::eventId).containsExactly("pending");
                    assertThat(sql.queryForObject("SELECT external_attempts FROM outbox_events WHERE event_id='pending'", Integer.class)).isEqualTo(++attempt);
                    now = next;
                }
                assertThat(repository.listPendingExternal(100)).extracting(OutboxEvent::attempts).containsExactly(28);
                assertThat(repository.listUnsynchronized(100)).isEmpty();
                assertThat(repository.listAfter(0, 100)).extracting(OutboxEvent::sequence).containsExactly(3L, 9L);
                assertThat(sql.queryForObject("SELECT sync_delivered_at FROM outbox_events WHERE event_id='pending'", java.sql.Timestamp.class).toInstant())
                        .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
                assertThat(sql.queryForObject("SELECT delivered_at=sync_delivered_at AND external_attempts=0 AND external_next_attempt_at IS NULL "
                        + "FROM outbox_events WHERE event_id='completed'", Boolean.class)).isTrue();
            }
            try (var context = new SpringApplication(ControlPlaneApplication.class).run(
                    "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                    "--spring.datasource.url=" + url, "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
                var repository = context.getBean(OutboxRepository.class);
                var sql = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
                assertThat(sql.queryForObject("SELECT external_attempts FROM outbox_events WHERE event_id='pending'", Integer.class)).isEqualTo(8);
                assertThat(repository.listDueExternal(100, now.minusSeconds(1))).isEmpty();
                assertThat(repository.listDueExternal(100, now)).extracting(OutboxEvent::eventId).containsExactly("pending");
                assertThat(repository.listUnsynchronized(100)).isEmpty();
                assertThat(sql.queryForObject("SELECT delivered_at IS NULL FROM outbox_events WHERE event_id='pending'", Boolean.class)).isTrue();
                String fresh = context.getBean(OutboxService.class).record(null, null, "fresh", "test.ready", "{}", null, null).eventId();
                repository.markSynchronized(fresh);
                sql.update("UPDATE outbox_events SET occurred_at=? WHERE event_id=?", java.sql.Timestamp.from(now.plusSeconds(1)), fresh);
                assertThat(repository.listDueExternal(1, now.plusSeconds(2))).extracting(OutboxEvent::eventId).containsExactly("pending");
            }
        }
    }

    @Test void failedExternalHeadDoesNotStarveLaterDeliverableEvents() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class, ReadyTargetConfiguration.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var events = context.getBean(OutboxService.class);
            for (int index = 0; index < 110; index++) {
                events.record(null, null, "old-" + index, "network.revoked", "{\"user_id\":\"departed-user\"}", 1L, 1L);
            }
            String ready = events.record(null, null, "ready", "test.ready", "{}", null, null).eventId();
            var dispatcher = context.getBean(RevocationDispatcher.class);
            for (int round = 0; round < 3; round++) dispatcher.dispatchPending();
            var sql = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            assertThat(sql.queryForObject("SELECT delivered_at IS NOT NULL FROM outbox_events WHERE event_id=?", Boolean.class, ready)).isTrue();
            assertThat(sql.queryForObject("SELECT count(*) FROM outbox_events WHERE type='network.revoked' AND delivered_at IS NULL", Integer.class)).isEqualTo(110);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReadyTargetConfiguration {
        @Bean RevocationTarget readyTarget() {
            return new RevocationTarget() {
                public boolean supports(String type) { return type.equals("test.ready"); }
                public void deliver(OutboxEvent event) { }
            };
        }
    }

    private static class SlowTarget implements RevocationTarget {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        public boolean supports(String type) { return type.equals("test.slow"); }
        public void deliver(OutboxEvent event) {
            entered.countDown();
            try { release.await(15, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("EXTERNAL_TARGET_UNAVAILABLE");
        }
    }

    private static class Listener implements WebSocket.Listener {
        final CompletableFuture<String> message = new CompletableFuture<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder partial = new StringBuilder();
        public void onOpen(WebSocket socket) { socket.request(1); }
        public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            partial.append(text);
            if (last) {
                String event = partial.toString();
                partial.setLength(0);
                if (event.contains("message.created")) message.complete(event);
            }
            socket.request(1);
            return null;
        }
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) { closed.complete(status); return null; }
        public void onError(WebSocket socket, Throwable failure) { message.completeExceptionally(new IllegalStateException("SYNC_FAILED")); }
    }
}
