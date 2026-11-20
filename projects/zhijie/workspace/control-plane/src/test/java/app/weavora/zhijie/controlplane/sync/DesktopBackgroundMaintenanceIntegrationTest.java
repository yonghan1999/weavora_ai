package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.message.MessageService;
import app.weavora.zhijie.controlplane.room.RoomService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class DesktopBackgroundMaintenanceIntegrationTest {
    private static Path workspace;
    private static Path shellTest;

    @BeforeAll static void prepareShellTest() throws Exception {
        var current = Path.of(System.getProperty("user.dir"));
        workspace = Files.isRegularFile(current.resolve("control-plane/pom.xml")) ? current : current.getParent();
        var target = Files.createDirectories(workspace.resolve("target"));
        var log = Files.createTempFile(target, "background-shell-build-", ".log");
        var builder = new ProcessBuilder("node", "tests/prepare-group-client.mjs")
                .directory(workspace.resolve("desktop/ui").toFile()).redirectError(log.toFile()).start();
        try {
            assertThat(builder.waitFor(600, TimeUnit.SECONDS)).as("Shell build deadline: %s", log).isTrue();
            assertThat(builder.exitValue()).as("Shell build: %s", log).isZero();
            shellTest = Path.of(new String(builder.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(shellTest).isAbsolute().isRegularFile();
        } finally {
            if (builder.isAlive()) { builder.descendants().forEach(ProcessHandle::destroyForcibly); builder.destroyForcibly(); }
        }
    }

    @Test void productionBackgroundLoopsRotateReconnectAndClearDisabledAccount() throws Exception {
        runBackgroundLoops(false);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_BACKGROUND_LONG", matches = "true")
    void productionBackgroundLoopsSurviveActualCredentialExpiry() throws Exception {
        runBackgroundLoops(true);
    }

    private void runBackgroundLoops(boolean realTime) throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=true",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identity = context.getBean(IdentityService.class);
            var repository = context.getBean(IdentityRepository.class);
            String user = UUID.randomUUID().toString();
            String password = UUID.randomUUID().toString();
            repository.insertUser(new UserAccount(user, "background@example.test", AccountStatus.ACTIVE,
                    context.getBean(CredentialProtector.class).hashPassword(password), Instant.now()));
            var tokens = identity.login("background@example.test", password, "background-loop");
            if (realTime) assertThat(tokens.accessExpiresAt()).isBetween(Instant.now().plusSeconds(840), Instant.now().plusSeconds(900));
            var group = context.getBean(GroupService.class).createGroup(user, "后台循环联调", null, "");
            var rooms = context.getBean(RoomService.class);
            var room = rooms.createRoom(user, group.id(), "自动续期活动", "", "", null);
            rooms.startActivity(user, room.id());
            var joined = context.getBean(ActivityCoordinator.class).join(user, room.id(), "background-loop", null, "background-probe");
            var json = context.getBean(ObjectMapper.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            var sync = context.getBean(SyncService.class);
            var log = Files.createTempFile(workspace.resolve("target"), "background-shell-", ".log");
            var process = new ProcessBuilder(shellTest.toString(), "sync::maintenance_probe::production_session_and_sync_loops",
                    "--exact", "--ignored", "--nocapture").directory(workspace.toFile()).redirectError(log.toFile()).start();
            try (var output = process.getOutputStream(); var input = process.inputReader(StandardCharsets.UTF_8)) {
                int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
                send(output, json, Map.of("base", "http://127.0.0.1:" + port, "tokens", tokens, "activity", joined, "group", group.id()));
                expect(process, input, "READY", 30);
                Runnable checkLease = () -> {
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM connection_leases WHERE id=? AND revoked_at IS NULL AND expires_at>now()",
                            Integer.class, joined.leaseId())).isOne();
                    assertThat(jdbc.queryForObject("SELECT state FROM activity_members WHERE activity_id=? AND user_id=?",
                            String.class, joined.activityId(), user)).isEqualTo("ACTIVE");
                    assertThat(jdbc.queryForObject("SELECT ipv4 FROM ip_allocations WHERE activity_id=? AND user_id=? AND released_at IS NULL",
                            String.class, joined.activityId(), user)).isEqualTo(joined.virtualIpv4());
                };
                int messages = 0;
                int rotatedMessages = 0;
                do {
                    assertThat(Instant.now()).isBefore(tokens.accessExpiresAt().plusSeconds(120));
                    var message = context.getBean(MessageService.class).post(user, group.id(), "background-loop-probe");
                    boolean rotated = repository.findSessionById(tokens.sessionId()).orElseThrow().generation() == 2L;
                    send(output, json, Map.of("messageId", message.id(), "rotated", rotated));
                    expect(process, input, "MESSAGE", 10);
                    checkLease.run();
                    assertThat(sync.sessionCount(user)).isOne();
                    if (rotated) rotatedMessages++;
                    System.out.println("BACKGROUND_MESSAGE " + ++messages + " ROTATED=" + rotated);
                    if (!realTime) {
                        if (messages == 2) break;
                        send(output, json, Map.of("expire", true));
                        expect(process, input, "ARMED", 5);
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                        while (repository.findSessionById(tokens.sessionId()).orElseThrow().generation() == 1L
                                && System.nanoTime() < deadline) Thread.sleep(50);
                        assertThat(repository.findSessionById(tokens.sessionId()).orElseThrow().generation()).isEqualTo(2L);
                    } else {
                        if (Instant.now().isAfter(tokens.accessExpiresAt().plusSeconds(15))) break;
                        Thread.sleep(15000);
                    }
                } while (true);
                assertThat(rotatedMessages).as("Messages received after automatic credential rotation").isPositive();
                if (realTime) {
                    assertThat(messages).isGreaterThanOrEqualTo(60);
                    assertThat(Instant.now()).isAfter(tokens.accessExpiresAt().plusSeconds(15));
                }
                assertThat(repository.findSessionById(tokens.sessionId()).orElseThrow().generation()).isEqualTo(2L);
                jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", user);
                send(output, json, Map.of("disabled", true));
                expect(process, input, "CLEARED", 25);
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).as("Background shell diagnostics: %s", log).isZero();
                assertThat(sync.sessionCount(user)).isZero();
                assertThat(Files.readString(log)).doesNotContain(tokens.accessToken(), tokens.refreshToken(), password);
            } finally {
                if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            }
        }
    }

    private void send(OutputStream output, ObjectMapper json, Object value) throws IOException {
        output.write(json.writeValueAsBytes(value)); output.write('\n'); output.flush();
    }

    private void expect(Process process, BufferedReader input, String expected, long seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (!input.ready()) {
                assertThat(process.isAlive()).as("Background probe alive before %s", expected).isTrue();
                Thread.sleep(10);
                continue;
            }
            String line = input.readLine();
            assertThat(line).isNotNull().doesNotContain("FAILED");
            if (line.startsWith("BACKGROUND_PROBE_")) {
                assertThat(line).isEqualTo("BACKGROUND_PROBE_" + expected);
                return;
            }
        }
        throw new AssertionError("Background probe signal deadline: " + expected);
    }
}
