package app.weavora.zhijie.controlplane.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.activity.AuthenticatedActivities;
import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import app.weavora.zhijie.controlplane.grant.GrantService;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.room.RoomService;
import app.weavora.zhijie.controlplane.sync.ExternalDeliveryChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class RelayAuthorityIntegrationTest {
    private static final String validationKey = UUID.randomUUID().toString();
    private static final String revocationKey = UUID.randomUUID().toString();
    private static EmbeddedPostgres postgres;
    private static ServletWebServerApplicationContext context;
    private static int managementPort;

    @BeforeAll static void start() throws Exception {
        var directory = Files.createTempDirectory(Path.of("target"), "relay-authority-build-");
        var build = new ProcessBuilder("cargo", "test", "--locked", "--offline", "-p", "zhijie-relay",
                "--test", "authority_loss", "--no-run").redirectErrorStream(true)
                .redirectOutput(directory.resolve("build.log").toFile()).start();
        try {
            assertThat(build.waitFor(180, TimeUnit.SECONDS)).as("Rust build: %s", directory).isTrue();
            assertThat(build.exitValue()).as("Rust build: %s", directory).isZero();
        } finally {
            if (build.isAlive()) { build.descendants().forEach(ProcessHandle::destroyForcibly); build.destroyForcibly(); }
        }
        try (var reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            managementPort = reservation.getLocalPort();
        }
        postgres = EmbeddedPostgres.start();
        context = (ServletWebServerApplicationContext)new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--zhijie.relay.management-key=" + validationKey,
                "--zhijie.relay.revocation-enabled=true", "--zhijie.relay.revocation-key=" + revocationKey,
                "--zhijie.relay.admin-url=http://127.0.0.1:" + managementPort);
    }

    @AfterAll static void stop() throws Exception {
        if (context != null) context.close();
        if (postgres != null) postgres.close();
    }

    @Test void realHttpOutageCannotKeepRevokedSourceOrDestinationAliveAndPushStillWorks() throws Exception {
        for (String mode : List.of("offline", "push-offline")) run(mode);
    }

    private void run(String mode) throws Exception {
        var users = new ArrayList<String>();
        for (int index = 0; index < 3; index++) {
            String user = UUID.randomUUID().toString();
            context.getBean(IdentityRepository.class).insertUser(new UserAccount(user, user + "@example.test",
                    AccountStatus.ACTIVE, "unused", Instant.now()));
            users.add(user);
        }
        var groups = context.getBean(GroupService.class);
        var group = groups.createGroup(users.getFirst(), "真实中继失联隔离", null, "");
        var directory = Files.createTempDirectory(Path.of("target"), "relay-authority-").toAbsolutePath();
        var transcript = new StringBuilder();
        var json = context.getBean(ObjectMapper.class);
        Process process = null;
        try (var proxy = new ValidationProxy(json)) {
            for (String user : users.subList(1, 3)) groups.acceptInvitation(user, groups.invite(users.getFirst(), group.id(), user).id());
            var rooms = context.getBean(RoomService.class);
            var room = rooms.createRoom(users.getFirst(), group.id(), "实际 HTTP 黑洞", "", "", null);
            String activity = rooms.startActivity(users.getFirst(), room.id()).id();
            var coordinator = context.getBean(ActivityCoordinator.class);
            var peers = new ArrayList<Map<String, String>>();
            var leases = new ArrayList<String>();
            for (int index = 0; index < 3; index++) {
                var joined = AuthenticatedActivities.join(context, users.get(index), room.id(), "device-" + index, null, "authority-" + index);
                leases.add(joined.leaseId());
                byte[] publicKey = new byte[32]; Arrays.fill(publicKey, (byte)(index + 1));
                var grant = context.getBean(GrantService.class).network(users.get(index), activity, "device-" + index,
                        Base64.getEncoder().encodeToString(publicKey));
                peers.add(Map.of("ticket", grant.networkTicket(), "ip", grant.virtualIpv4()));
            }
            process = new ProcessBuilder("cargo", "test", "--locked", "--offline", "-p", "zhijie-relay",
                    "--test", "authority_loss", "real_http_loss_bounds_quic_forwarding_without_client_stop",
                    "--", "--ignored", "--exact", "--nocapture")
                    .redirectError(directory.resolve("rust.log").toFile()).start();
            try (var output = process.outputWriter(StandardCharsets.UTF_8); var input = process.inputReader(StandardCharsets.UTF_8)) {
                send(output, json.writeValueAsString(Map.of("controlPlaneUrl", proxy.url(), "validationKey", validationKey,
                        "revocationKey", revocationKey, "managementAddress", "127.0.0.1:" + managementPort,
                        "mode", mode, "peers", peers)));
                expect(input, transcript, "RELAY_AUTHORITY_READY", System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
                proxy.blockedTicket.set(peers.get(1).get("ticket"));
                assertThat(proxy.pending.await(3, TimeUnit.SECONDS)).as("Actual HTTP request must be pending: %s", directory).isTrue();
                assertThat(coordinator.leave(users.get(1), activity, "device-1", leases.get(1))).isTrue();
                long confirmed = System.nanoTime();
                long deadline = confirmed + TimeUnit.SECONDS.toNanos(5);
                send(output, "REVOKED");
                expect(input, transcript, "RELAY_AUTHORITY_REVOKED_OBSERVED", deadline);
                if (mode.equals("push-offline")) {
                    context.getBean(SyncOutboxDispatcher.class).dispatchPending();
                    context.getBean(ExternalRevocationDispatcher.class).dispatchPending(ExternalDeliveryChannel.NETWORK);
                    var sql = context.getBean(JdbcTemplate.class);
                    assertThat(sql.queryForObject("SELECT count(*) FROM outbox_events WHERE activity_id=? AND type='network.revoked' AND delivered_at IS NOT NULL",
                            Integer.class, activity)).isEqualTo(1);
                    send(output, "PUSH_APPLIED");
                    expect(input, transcript, "RELAY_AUTHORITY_PUSH_CLOSED", confirmed + TimeUnit.SECONDS.toNanos(1));
                    transcript.append("REAL_RELAY_PUSH_CLOSED observed_ms=")
                            .append(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - confirmed)).append('\n');
                }
                expect(input, transcript, "RELAY_AUTHORITY_ISOLATED", deadline);
                long observedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - confirmed);
                assertThat(System.nanoTime()).isLessThan(deadline);
                assertThat(proxy.successes(peers.getFirst().get("ticket"))).isGreaterThanOrEqualTo(3);
                assertThat(proxy.successes(peers.get(2).get("ticket"))).isGreaterThanOrEqualTo(3);
                proxy.blockedTicket.set(null);
                send(output, "AUTHORITY_RESTORED");
                expect(input, transcript, "RELAY_AUTHORITY_STALE_RECONNECT_REJECTED", System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
                assertThat(proxy.denied.get()).as("Restored real authority must reject the revoked ticket").isPositive();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("Rust cleanup: %s", directory).isTrue();
                assertThat(process.exitValue()).as("Rust probe: %s", directory).isZero();
                transcript.append("REAL_RELAY_AUTHORITY_ISOLATED mode=").append(mode).append(" observed_ms=").append(observedMillis).append('\n');
                String logs = Files.readString(directory.resolve("rust.log")) + transcript;
                assertThat(logs).doesNotContain(validationKey, revocationKey);
                for (var peer : peers) assertThat(logs).doesNotContain(peer.get("ticket"));
            }
        } finally {
            Files.writeString(directory.resolve("scenario.log"), transcript);
            if (process != null && process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            groups.dissolveGroup(users.getFirst(), group.id());
        }
        System.out.println("RELAY_AUTHORITY_EVIDENCE mode=" + mode + " directory=" + directory);
    }

    private static void send(BufferedWriter output, String line) throws Exception { output.write(line); output.newLine(); output.flush(); }

    private static void expect(BufferedReader input, StringBuilder transcript, String expected, long deadline) throws Exception {
        while (System.nanoTime() < deadline) {
            if (!input.ready()) { Thread.sleep(5); continue; }
            String line = input.readLine();
            assertThat(line).as("Rust closed before %s", expected).isNotNull();
            transcript.append(line).append('\n');
            if (line.equals(expected)) return;
        }
        throw new AssertionError("Rust signal deadline: " + expected);
    }

    private static final class ValidationProxy implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        private final AtomicReference<String> blockedTicket = new AtomicReference<>();
        private final CountDownLatch pending = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final ConcurrentHashMap<String, AtomicInteger> successful = new ConcurrentHashMap<>();
        private final AtomicInteger denied = new AtomicInteger();

        ValidationProxy(ObjectMapper json) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/internal/relay/v1/validate", exchange -> {
                try (exchange) {
                    byte[] body = exchange.getRequestBody().readNBytes(8193);
                    if (body.length > 8192) { exchange.sendResponseHeaders(413, -1); return; }
                    String ticket = json.readTree(body).path("ticket").asText();
                    if (ticket.equals(blockedTicket.get())) {
                        pending.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return;
                    }
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + "/internal/relay/v1/validate"))
                            .timeout(Duration.ofSeconds(2)).header("Content-Type", "application/json")
                            .header("X-Relay-Key", exchange.getRequestHeaders().getFirst("X-Relay-Key"))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                    var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() == 200) successful.computeIfAbsent(ticket, ignored -> new AtomicInteger()).incrementAndGet();
                    if (response.statusCode() == 403) denied.incrementAndGet();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                    exchange.getResponseBody().write(response.body());
                } catch (java.io.IOException failed) {
                    exchange.close();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        int successes(String ticket) { return successful.getOrDefault(ticket, new AtomicInteger()).get(); }

        @Override public void close() {
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
            http.close();
        }
    }
}
