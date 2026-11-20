package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;
import app.weavora.zhijie.controlplane.activity.AuthenticatedActivities;
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
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
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

class DesktopSessionIntegrationTest {
    private static Path workspace;
    private static Path shellTest;

    @BeforeAll static void prepareShellTest() throws Exception {
        var current = Path.of(System.getProperty("user.dir"));
        workspace = Files.isRegularFile(current.resolve("control-plane/pom.xml")) ? current : current.getParent();
        var logs = Files.createDirectories(workspace.resolve("target"));
        var buildLog = Files.createTempFile(logs, "desktop-session-build-", ".log");
        var builder = new ProcessBuilder("node", "tests/prepare-group-client.mjs")
                .directory(workspace.resolve("desktop/ui").toFile()).redirectError(buildLog.toFile()).start();
        try {
            assertThat(builder.waitFor(600, TimeUnit.SECONDS)).as("Shell build deadline: %s", buildLog).isTrue();
            assertThat(builder.exitValue()).as("Shell build: %s", buildLog).isZero();
            shellTest = Path.of(new String(builder.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(shellTest).isAbsolute().isRegularFile();
        } finally {
            if (builder.isAlive()) { builder.descendants().forEach(ProcessHandle::destroyForcibly); builder.destroyForcibly(); }
        }
    }

    @Test void actualShellMaintenanceRotatesCredentialsKeepsLeaseAndClearsDisabledAccount() throws Exception {
        runMaintenance(false, false, false, false);
    }
    @Test void queuedSyncRecoveryCannotReplayCredentialsRotatedByHeartbeat() throws Exception {
        runMaintenance(false, true, false, false);
    }
    @Test void actualShellLogoutRevokesCredentialsLeaseAndNetworkGrant() throws Exception {
        runMaintenance(false, false, true, false);
    }
    @Test void scheduledLifecyclePreservesActivityAcrossCredentialRotation() throws Exception {
        runMaintenance(false, true, false, true);
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_SESSION_LONG", matches = "true")
    void actualFifteenMinuteCredentialsRenewWithoutDroppingActivity() throws Exception {
        runMaintenance(true, true, false, true);
    }
    private void runMaintenance(boolean realTime, boolean syncRace, boolean logout, boolean scheduled) throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=" + scheduled,
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identity = context.getBean(IdentityService.class);
            String user = UUID.randomUUID().toString();
            context.getBean(IdentityRepository.class).insertUser(new UserAccount(user, "shell-session@example.test", AccountStatus.ACTIVE,
                    context.getBean(CredentialProtector.class).hashPassword("shell-session-password-123"), Instant.now()));
            var tokens = identity.login("shell-session@example.test", "shell-session-password-123", "shell-session");
            var group = context.getBean(GroupService.class).createGroup(user, "真实桌面续期", null, "");
            var rooms = context.getBean(RoomService.class);
            var room = rooms.createRoom(user, group.id(), "续期活动", "", "", null);
            rooms.startActivity(user, room.id());
            var joined = AuthenticatedActivities.join(context, user, room.id(), "shell-session", null, "session-probe");
            var otherSession = logout ? identity.login("shell-session@example.test", "shell-session-password-123", "other-device") : null;
            var networkGrant = logout ? context.getBean(app.weavora.zhijie.controlplane.grant.GrantService.class).network(
                    user, joined.activityId(), joined.deviceId(), java.util.Base64.getEncoder().encodeToString(new byte[]{
                            1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32})) : null;
            var json = context.getBean(ObjectMapper.class);
            int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var http = HttpClient.newHttpClient();
            var networkValidation = logout ? HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/network-access/validate"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("ticket", networkGrant.networkTicket())))).build() : null;
            if (logout) assertThat(http.send(networkValidation, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            var log = Files.createTempFile(workspace.resolve("target"), "desktop-session-rust-", ".log");
            var process = new ProcessBuilder(shellTest.toString(), "session::tests::real_control_plane_refresh_and_disabled_cleanup",
                    "--exact", "--ignored", "--nocapture")
                    .directory(workspace.toFile()).redirectError(log.toFile()).start();
            try (var output = process.getOutputStream(); var input = process.inputReader(StandardCharsets.UTF_8)) {
                send(output, json, Map.of("base", "http://127.0.0.1:" + port, "tokens", tokens, "activity", joined,
                        "realTime", realTime, "syncRace", syncRace, "logout", logout, "scheduled", scheduled));
                if (logout) {
                    expect(process, input, "SESSION_PROBE_LOGGED_OUT", 90, null);
                    var jdbc = context.getBean(JdbcTemplate.class);
                    assertThat(context.getBean(IdentityRepository.class).findSessionById(tokens.sessionId()).orElseThrow().revokedAt()).isNotNull();
                    assertThat(identity.authenticate("Bearer " + otherSession.accessToken()).id()).isEqualTo(user);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM connection_leases WHERE id = ? AND revoked_at IS NOT NULL",
                            Integer.class, joined.leaseId())).isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT state FROM activity_members WHERE activity_id = ? AND user_id = ?",
                            String.class, joined.activityId(), user)).isEqualTo("LEFT");
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM account_activity WHERE user_id = ?", Integer.class, user)).isZero();
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM ip_allocations WHERE activity_id = ? AND user_id = ? AND released_at IS NULL",
                            Integer.class, joined.activityId(), user)).isZero();
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM activity_grants WHERE id = ? AND revoked_at IS NOT NULL",
                            Integer.class, networkGrant.grantId())).isEqualTo(1);
                    var rejected = http.send(networkValidation, HttpResponse.BodyHandlers.ofString());
                    assertThat(rejected.statusCode()).isEqualTo(403);
                    assertThat(json.readTree(rejected.body()).path("error").path("code").asText()).isEqualTo("NETWORK_GRANT_INVALID");
                    assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(process.exitValue()).as("Shell diagnostics: %s", log).isZero();
                    assertThat(Files.readString(log)).doesNotContain(tokens.accessToken(), tokens.refreshToken(),
                            otherSession.accessToken(), otherSession.refreshToken(), networkGrant.networkTicket());
                    return;
                }
                var jdbc = context.getBean(JdbcTemplate.class);
                Runnable checkLease = () -> {
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM connection_leases WHERE id = ? AND revoked_at IS NULL AND expires_at > now()",
                            Integer.class, joined.leaseId())).isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT ipv4 FROM ip_allocations WHERE activity_id = ? AND user_id = ? AND released_at IS NULL",
                            String.class, joined.activityId(), user)).isEqualTo(joined.virtualIpv4());
                    assertThat(jdbc.queryForObject("SELECT state FROM activity_members WHERE activity_id = ? AND user_id = ?",
                            String.class, joined.activityId(), user)).isEqualTo("ACTIVE");
                };
                Runnable onRotation = syncRace ? () -> {
                    if (!scheduled) context.getBean(SyncService.class).revalidateSessions();
                } : null;
                ProbeCheck onTick = realTime || scheduled ? () -> {
                    checkLease.run();
                    if (scheduled) {
                        var message = context.getBean(MessageService.class).post(user, group.id(), "scheduled-session-probe");
                        send(output, json, Map.of("messageId", message.id()));
                    }
                } : null;
                expect(process, input, "SESSION_PROBE_REFRESHED", realTime ? 1050 : 90, onTick, onRotation);
                checkLease.run();
                if (realTime) assertThat(Instant.now()).isAfter(tokens.accessExpiresAt().plusSeconds(15));
                assertThat(context.getBean(IdentityRepository.class).findSessionById(tokens.sessionId()).orElseThrow().generation()).isEqualTo(2L);
                context.getBean(JdbcTemplate.class).update("UPDATE users SET status = 'DISABLED' WHERE id = ?", user);
                send(output, json, Map.of("disabled", true));
                expect(process, input, "SESSION_PROBE_CLEARED", 90, null);
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).as("Shell diagnostics: %s", log).isZero();
                assertThat(Files.readString(log)).doesNotContain(tokens.accessToken(), tokens.refreshToken());
            } finally {
                if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            }
        }
    }
    private void send(OutputStream output, ObjectMapper json, Object value) throws IOException {
        output.write(json.writeValueAsBytes(value)); output.write('\n'); output.flush();
    }
    private void expect(Process process, BufferedReader input, String expected, long seconds, Runnable checkLease) throws Exception {
        expect(process, input, expected, seconds, checkLease == null ? null : checkLease::run, null);
    }
    private void expect(Process process, BufferedReader input, String expected, long seconds, ProbeCheck checkLease,
                        Runnable onRotation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        int ticks = 0;
        int rotations = 0;
        int messages = 0;
        while (System.nanoTime() < deadline) {
            if (!input.ready()) {
                assertThat(process.isAlive()).as("Shell probe exited before expected signal").isTrue();
                Thread.sleep(10);
                continue;
            }
            String line = input.readLine();
            assertThat(line).as("Shell probe still running").isNotNull();
            assertThat(line).as("Shell probe test result").doesNotContain("FAILED", "test result: FAILED");
            if (line.equals("SESSION_PROBE_TICK") && checkLease != null) {
                checkLease.run();
                System.out.println("SESSION_LONG_TICK " + ++ticks);
                continue;
            }
            if (line.equals("SESSION_PROBE_ROTATED") && onRotation != null) {
                assertThat(++rotations).as("Exactly one credential rotation").isEqualTo(1);
                onRotation.run();
                System.out.println("SESSION_SYNC_ROTATED");
                continue;
            }
            if (line.equals("SESSION_SYNC_MESSAGE")) {
                assertThat(++messages).as("Each maintenance round receives its own message").isEqualTo(ticks);
                System.out.println("SESSION_SYNC_MESSAGE " + messages);
                continue;
            }
            if (line.startsWith("SESSION_PROBE_")) {
                assertThat(line).isEqualTo(expected);
                if (onRotation != null) assertThat(rotations).isEqualTo(1);
                if (checkLease != null) assertThat(messages).isEqualTo(ticks);
                return;
            }
        }
        throw new AssertionError("Shell probe signal deadline");
    }
    @FunctionalInterface
    private interface ProbeCheck {
        void run() throws Exception;
    }
}
