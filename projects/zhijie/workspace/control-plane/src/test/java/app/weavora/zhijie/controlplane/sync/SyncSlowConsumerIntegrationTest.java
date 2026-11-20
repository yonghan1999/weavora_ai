package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.identity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

class SyncSlowConsumerIntegrationTest {
    @Test void blockedRealSocketCannotHoldRevokedUserCleanupPastItsBudget() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var credentials = context.getBean(CredentialProtector.class);
            var identities = context.getBean(IdentityRepository.class);
            var identity = context.getBean(IdentityService.class);
            var tokens = new ArrayList<String>();
            var users = new ArrayList<String>();
            for (int index = 0; index < 2; index++) {
                String user = UUID.randomUUID().toString();
                String session = UUID.randomUUID().toString();
                String secret = credentials.randomSecret();
                identities.insertUser(new UserAccount(user, user + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
                identities.insertSession(new AuthSession(session, user, "slow-consumer-" + index, credentials.hashToken(credentials.randomSecret()), credentials.hashToken(secret),
                        Instant.now().plusSeconds(120), Instant.now().plusSeconds(120), null, 1L));
                users.add(user);
                tokens.add(session + "." + secret);
            }
            var sync = context.getBean(SyncService.class);
            int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var received = new CompletableFuture<String>();
            try (var http = HttpClient.newHttpClient(); var slow = new Socket(); var workers = Executors.newFixedThreadPool(2)) {
                var healthy = http.newWebSocketBuilder().header("Authorization", "Bearer " + tokens.get(1))
                        .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/ws?cursor=0"), new WebSocket.Listener() {
                            private final StringBuilder partial = new StringBuilder();
                            public void onOpen(WebSocket socket) { socket.request(1); }
                            public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
                                partial.append(text);
                                if (last) { received.complete(partial.toString()); partial.setLength(0); }
                                socket.request(1);
                                return null;
                            }
                        }).get(5, TimeUnit.SECONDS);
                try {
                    connectUnreadPeer(slow, tokens.getFirst(), port);
                    long registered = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (sync.sessionCount(users.getFirst()) == 0 && System.nanoTime() < registered) Thread.sleep(2);
                    assertThat(sync.sessionCount(users.getFirst())).isEqualTo(1);
                    Future<?> blocked = null;
                    int writes = 0;
                    String payload = "{\"transportProbe\":\"" + "x".repeat(32768) + "\"}";
                    for (int index = 1; index <= 512; index++) {
                        var event = event(index, users.getFirst(), payload);
                        var sending = workers.submit(() -> sync.broadcast(event));
                        try { sending.get(200, TimeUnit.MILLISECONDS); }
                        catch (TimeoutException pending) { blocked = sending; writes = index; break; }
                    }
                    assertThat(blocked).as("Actual unread TCP peer must stall a production WebSocket write").isNotNull();
                    assertThat(blocked.isDone()).isFalse();
                    identity.logout(tokens.getFirst());
                    long confirmed = System.nanoTime();
                    var closing = workers.submit(() -> sync.closeUser(users.getFirst()));
                    closing.get(3, TimeUnit.SECONDS);
                    blocked.get(1, TimeUnit.SECONDS);
                    assertThat(sync.sessionCount(users.getFirst())).isZero();
                    sync.broadcast(event(10000, users.get(1), "{}"));
                    var actual = context.getBean(ObjectMapper.class).readTree(received.get(1, TimeUnit.SECONDS));
                    assertThat(actual.path("sequence").asLong()).isEqualTo(10000);
                    assertThat(sync.sessionCount(users.get(1))).isEqualTo(1);
                    System.out.println("REAL_SLOW_SYNC_BOUNDED writes=" + writes + " cleanup_ms="
                            + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - confirmed) + " healthy_delivery=true");
                } finally {
                    slow.close();
                    healthy.abort();
                }
            }
        }
    }

    @Test void revocationClosesItsTargetBeforeBroadcastingToSeveralUnreadPeers() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var credentials = context.getBean(CredentialProtector.class);
            var identities = context.getBean(IdentityRepository.class);
            var tokens = new ArrayList<String>();
            var users = new ArrayList<String>();
            for (int index = 0; index < 2; index++) {
                String user = UUID.randomUUID().toString();
                String session = UUID.randomUUID().toString();
                String secret = credentials.randomSecret();
                identities.insertUser(new UserAccount(user, user + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
                identities.insertSession(new AuthSession(session, user, "revocation-probe-" + index,
                        credentials.hashToken(credentials.randomSecret()), credentials.hashToken(secret),
                        Instant.now().plusSeconds(120), Instant.now().plusSeconds(120), null, 1L));
                users.add(user);
                tokens.add(session + "." + secret);
            }
            var sync = context.getBean(SyncService.class);
            int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var closed = new CompletableFuture<Integer>();
            try (var http = HttpClient.newHttpClient(); var first = new Socket(); var second = new Socket(); var third = new Socket();
                    var workers = Executors.newFixedThreadPool(2)) {
                var target = http.newWebSocketBuilder().header("Authorization", "Bearer " + tokens.get(1))
                        .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/ws?cursor=0"), new WebSocket.Listener() {
                            public void onOpen(WebSocket socket) { socket.request(1); }
                            public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
                                socket.request(1);
                                return null;
                            }
                            public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
                                closed.complete(status);
                                return null;
                            }
                        }).get(5, TimeUnit.SECONDS);
                try {
                    for (var peer : List.of(first, second, third)) connectUnreadPeer(peer, tokens.getFirst(), port);
                    long registered = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (sync.sessionCount(users.getFirst()) != 3 && System.nanoTime() < registered) Thread.sleep(2);
                    assertThat(sync.sessionCount(users.getFirst())).isEqualTo(3);
                    Future<?> blocked = null;
                    int writes = 0;
                    String payload = "{\"transportProbe\":\"" + "x".repeat(32768) + "\"}";
                    for (int index = 1; index <= 512; index++) {
                        var event = event(index, users.getFirst(), payload);
                        var sending = workers.submit(() -> sync.broadcast(event));
                        try { sending.get(200, TimeUnit.MILLISECONDS); }
                        catch (TimeoutException pending) { blocked = sending; writes = index; break; }
                    }
                    assertThat(blocked).as("Three actual unread TCP peers must apply backpressure").isNotNull();
                    assertThat(blocked.isDone()).isFalse();
                    var event = new OutboxEvent(10000L, "revocation-probe", null, null, users.get(1), "network.revoked",
                            "{\"user_id\":\"" + users.get(1) + "\"}", 1L, 1L, Instant.now(), null, 0);
                    long started = System.nanoTime();
                    var delivery = workers.submit(() -> context.getBean(SyncRevocationTarget.class).deliver(event));
                    assertThat(closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    assertThat(sync.sessionCount(users.get(1))).isZero();
                    blocked.get(10, TimeUnit.SECONDS);
                    delivery.get(10, TimeUnit.SECONDS);
                    assertThat(sync.sessionCount(users.getFirst())).isZero();
                    System.out.println("REAL_MULTI_SLOW_SYNC_REVOCATION peers=3 writes=" + writes + " target_close_ms=" + elapsed);
                } finally {
                    first.close();
                    second.close();
                    third.close();
                    target.abort();
                }
            }
        }
    }

    @Test void scheduledUnreadPeersCannotDelayAnUnrelatedIdleLogout() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var credentials = context.getBean(CredentialProtector.class);
            var identities = context.getBean(IdentityRepository.class);
            var tokens = new ArrayList<String>();
            var users = new ArrayList<String>();
            for (int index = 0; index < 2; index++) {
                String user = UUID.randomUUID().toString();
                String session = UUID.randomUUID().toString();
                String secret = credentials.randomSecret();
                identities.insertUser(new UserAccount(user, user + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
                identities.insertSession(new AuthSession(session, user, "scheduled-slow-" + index,
                        credentials.hashToken(credentials.randomSecret()), credentials.hashToken(secret),
                        Instant.now().plusSeconds(120), Instant.now().plusSeconds(120), null, 1L));
                users.add(user);
                tokens.add(session + "." + secret);
            }
            var sync = context.getBean(SyncService.class);
            var outbox = context.getBean(OutboxService.class);
            var sql = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var closed = new CompletableFuture<Integer>();
            try (var http = HttpClient.newHttpClient(); var first = new Socket(); var second = new Socket();
                    var third = new Socket(); var fourth = new Socket()) {
                var idle = http.newWebSocketBuilder().header("Authorization", "Bearer " + tokens.get(1))
                        .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/ws?cursor=0"), new WebSocket.Listener() {
                            public void onOpen(WebSocket socket) { socket.request(1); }
                            public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
                                socket.request(1);
                                return null;
                            }
                            public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
                                closed.complete(status);
                                return null;
                            }
                        }).get(5, TimeUnit.SECONDS);
                try {
                    for (var peer : List.of(first, second, third, fourth)) connectUnreadPeer(peer, tokens.getFirst(), port);
                    long registered = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (sync.sessionCount(users.getFirst()) != 4 && System.nanoTime() < registered) Thread.sleep(2);
                    assertThat(sync.sessionCount(users.getFirst())).isEqualTo(4);
                    OutboxEvent blocked = null;
                    int writes = 0;
                    String payload = "{\"transportProbe\":\"" + "x".repeat(32768) + "\"}";
                    for (int index = 1; index <= 32; index++) {
                        var event = outbox.record(null, null, users.getFirst(), "user.changed", payload, null, null);
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                        while (System.nanoTime() < deadline) {
                            if (scheduledTransportWriteIsBlocked()) {
                                Thread.sleep(200);
                                if (scheduledTransportWriteIsBlocked()) { blocked = event; writes = index; break; }
                            }
                            if (Boolean.TRUE.equals(sql.queryForObject(
                                    "SELECT sync_delivered_at IS NOT NULL FROM outbox_events WHERE event_id=?", Boolean.class, event.eventId()))) break;
                            Thread.sleep(2);
                        }
                        if (blocked != null) break;
                    }
                    assertThat(blocked).as("Production scheduled Outbox must be blocked in an actual native WebSocket write").isNotNull();
                    assertThat(sql.queryForObject("SELECT sync_delivered_at IS NULL FROM outbox_events WHERE event_id=?",
                            Boolean.class, blocked.eventId())).isTrue();
                    context.getBean(IdentityService.class).logout(tokens.get(1));
                    long confirmed = System.nanoTime();
                    assertThat(closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - confirmed);
                    assertThat(sync.sessionCount(users.get(1))).isZero();
                    assertThat(scheduledTransportWriteIsBlocked()).isTrue();
                    System.out.println("REAL_SCHEDULED_SLOW_SYNC_IDLE_LOGOUT peers=4 writes=" + writes
                            + " logout_close_ms=" + elapsed + " broadcast_still_blocked=true");
                } finally {
                    first.close();
                    second.close();
                    third.close();
                    fourth.close();
                    idle.abort();
                }
            }
        }
    }

    private boolean scheduledTransportWriteIsBlocked() {
        return Thread.getAllStackTraces().values().stream().anyMatch(frames -> {
            boolean scheduled = false;
            boolean transport = false;
            for (var frame : frames) {
                if (frame.getClassName().equals("app.weavora.zhijie.controlplane.revocation.SyncOutboxDispatcher")
                        && frame.getMethodName().equals("dispatchPending")) scheduled = true;
                if (frame.getClassName().equals("org.apache.tomcat.websocket.WsRemoteEndpointImplBase")
                        && frame.getMethodName().equals("sendMessageBlock")) transport = true;
            }
            return scheduled && transport;
        });
    }

    private void connectUnreadPeer(Socket socket, String token, int port) throws Exception {
        socket.setReceiveBufferSize(1024);
        socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
        socket.setSoTimeout(2000);
        String handshake = "GET /api/v1/ws?cursor=0 HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nAuthorization: Bearer " + token
                + "\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13"
                + "\r\nSec-WebSocket-Key: " + Base64.getEncoder().encodeToString(new byte[16]) + "\r\n\r\n";
        socket.getOutputStream().write(handshake.getBytes(StandardCharsets.US_ASCII));
        var headers = new StringBuilder();
        while (!headers.toString().endsWith("\r\n\r\n")) {
            int value = socket.getInputStream().read();
            assertThat(value).isGreaterThanOrEqualTo(0);
            headers.append((char)value);
            assertThat(headers.length()).isLessThan(8192);
        }
        assertThat(headers.toString()).startsWith("HTTP/1.1 101");
    }

    private OutboxEvent event(long sequence, String user, String payload) {
        return new OutboxEvent(sequence, "slow-probe-" + sequence, null, null, user, "user.changed", payload,
                null, null, Instant.now(), null, 0);
    }
}
