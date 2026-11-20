package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.activity.AuthenticatedActivities;
import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import app.weavora.zhijie.controlplane.activity.ActivityModerationService;
import app.weavora.zhijie.controlplane.activity.JoinResult;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.integration.TcpTestLink;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class DesktopBackgroundMaintenanceIntegrationTest {
    private enum Outage { NONE, SHORT, REVOKED, LONG }
    private enum Revocation { LEAVE, KICK, LEAVE_GROUP, REMOVE_MEMBER, END_ACTIVITY, DELETE_ROOM, DISABLE, LOGOUT, TAKEOVER, SWITCH_ROOM, DISSOLVE }
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
        runBackgroundLoops(false, Outage.NONE);
    }

    @Test void productionBackgroundLoopsRecoverShortOutageWithoutChangingLease() throws Exception {
        runBackgroundLoops(false, Outage.SHORT);
    }

    @Test void productionBackgroundLoopsRejectAnActivityRevokedDuringOutage() throws Exception {
        runBackgroundLoops(false, Outage.REVOKED);
    }

    @Test void productionBackgroundLoopsReleaseExpiredOfflineActivityWithoutRevivingIt() throws Exception {
        runBackgroundLoops(false, Outage.LONG);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_BACKGROUND_LONG", matches = "true")
    void productionBackgroundLoopsSurviveActualCredentialExpiry() throws Exception {
        runBackgroundLoops(true, Outage.NONE);
    }

    @ParameterizedTest
    @EnumSource(Revocation.class)
    void productionBackgroundLoopsClearOnlineRevocationsWithinFiveSeconds(Revocation mode) throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=true",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identity = context.getBean(IdentityService.class);
            var identities = context.getBean(IdentityRepository.class);
            var groups = context.getBean(GroupService.class);
            var rooms = context.getBean(RoomService.class);
            var activities = context.getBean(ActivityCoordinator.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            var json = context.getBean(ObjectMapper.class);
            String owner = UUID.randomUUID().toString(), member = UUID.randomUUID().toString();
            String password = UUID.randomUUID().toString();
            String hash = context.getBean(CredentialProtector.class).hashPassword(password);
            identities.insertUser(new UserAccount(owner, "owner-online@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            identities.insertUser(new UserAccount(member, "member-online@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            var tokens = identity.login("member-online@example.test", password, "online-loop");
            var observerTokens = identity.login("owner-online@example.test", password, "observer-loop");
            var group = groups.createGroup(owner, "在线撤销联调", null, "");
            groups.acceptInvitation(member, groups.invite(owner, group.id(), member).id());
            var room = rooms.createRoom(owner, group.id(), "后台撤销", "", "", null);
            rooms.startActivity(owner, room.id());
            var joined = AuthenticatedActivities.join(context, member, room.id(), "online-loop", null, "online-probe");
            var observerJoined = AuthenticatedActivities.join(context, owner, room.id(), "observer-loop", null, "observer-probe");
            int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            try (var subject = new BackgroundProbe(json, port, tokens, joined, group.id());
                    var observer = new BackgroundProbe(json, port, observerTokens, observerJoined, group.id())) {
                var baseline = context.getBean(MessageService.class).post(owner, group.id(), "before-online-revocation");
                subject.send(Map.of("messageId", baseline.id()));
                observer.send(Map.of("messageId", baseline.id()));
                subject.expect("MESSAGE", 10);
                observer.expect("MESSAGE", 10);
                assertThat(context.getBean(SyncService.class).sessionCount(member)).isOne();
                assertThat(context.getBean(SyncService.class).sessionCount(owner)).isOne();
                JoinResult replacement = null;
                switch (mode) {
                    case LEAVE -> assertThat(activities.leave(member, joined.activityId(), joined.deviceId(), joined.leaseId())).isTrue();
                    case KICK -> context.getBean(ActivityModerationService.class).kick(owner, joined.activityId(), member, joined.memberGeneration());
                    case LEAVE_GROUP -> groups.leaveGroup(member, group.id());
                    case REMOVE_MEMBER -> groups.removeMember(owner, group.id(), member);
                    case END_ACTIVITY -> rooms.endActivity(owner, joined.activityId());
                    case DELETE_ROOM -> rooms.deleteRoom(owner, room.id());
                    case DISABLE -> jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", member);
                    case LOGOUT -> identity.logout(tokens.accessToken());
                    case TAKEOVER -> replacement = AuthenticatedActivities.join(context, member, room.id(), "other-device", null, "takeover-probe");
                    case SWITCH_ROOM -> {
                        var target = rooms.createRoom(owner, group.id(), "切换目标", "", "", null);
                        rooms.startActivity(owner, target.id());
                        replacement = AuthenticatedActivities.join(context, member, target.id(), "online-loop", null, "switch-probe");
                    }
                    case DISSOLVE -> groups.dissolveGroup(owner, group.id());
                }
                long confirmed = System.nanoTime();
                subject.send(Map.of("revocation", mode.name()));
                subject.expect("REVOKED_ONLINE", 5);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - confirmed);
                assertThat(elapsed).as("Online local cleanup after server confirmation: %s", mode).isLessThanOrEqualTo(5000);
                subject.expect("STABLE", 3);
                boolean roomRevoked = mode == Revocation.END_ACTIVITY || mode == Revocation.DELETE_ROOM || mode == Revocation.DISSOLVE;
                if (roomRevoked) {
                    observer.send(Map.of("revocation", mode == Revocation.DISSOLVE ? "DISSOLVE" : "END_ACTIVITY"));
                    observer.expect("REVOKED_ONLINE", 5);
                    observer.expect("STABLE", 3);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM account_activity WHERE user_id=?", Integer.class, owner)).isZero();
                } else {
                    var after = context.getBean(MessageService.class).post(owner, group.id(), "unaffected-observer");
                    observer.send(Map.of("messageId", after.id(), "expectConnected", true));
                    observer.expect("MESSAGE", 5);
                    activities.heartbeat(owner, observerJoined.activityId(), observerJoined.deviceId(), observerJoined.leaseId(),
                            observerJoined.activityGeneration(), observerJoined.memberGeneration());
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM connection_leases WHERE id=? AND revoked_at IS NULL",
                            Integer.class, observerJoined.leaseId())).isOne();
                    assertThat(jdbc.queryForObject("SELECT ipv4 FROM ip_allocations WHERE activity_id=? AND user_id=? AND released_at IS NULL",
                            String.class, observerJoined.activityId(), owner)).isEqualTo(observerJoined.virtualIpv4());
                }
                assertThat(jdbc.queryForObject("SELECT count(*) FROM connection_leases WHERE id=? AND revoked_at IS NOT NULL",
                        Integer.class, joined.leaseId())).isOne();
                if (replacement == null) {
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM account_activity WHERE user_id=?", Integer.class, member)).isZero();
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM ip_allocations WHERE activity_id=? AND user_id=? AND released_at IS NULL",
                            Integer.class, joined.activityId(), member)).isZero();
                } else {
                    assertThat(jdbc.queryForObject("SELECT activity_id FROM account_activity WHERE user_id=?", String.class, member)).isEqualTo(replacement.activityId());
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM connection_leases WHERE id=? AND revoked_at IS NULL",
                            Integer.class, replacement.leaseId())).isOne();
                    if (mode == Revocation.TAKEOVER) assertThat(replacement.memberGeneration()).isGreaterThan(joined.memberGeneration());
                    else assertThat(replacement.activityId()).isNotEqualTo(joined.activityId());
                }
                subject.stop(password);
                observer.stop(password);
                System.out.println("BACKGROUND_ONLINE_REVOCATION " + mode + " CLEANUP_MS=" + elapsed);
            }
        }
    }

    private final class BackgroundProbe implements AutoCloseable {
        private final ObjectMapper json;
        private final AuthTokens tokens;
        private final Path log;
        private final Process process;
        private final OutputStream output;
        private final BufferedReader input;

        BackgroundProbe(ObjectMapper json, int port, AuthTokens tokens, JoinResult joined, String group) throws Exception {
            this.json = json;
            this.tokens = tokens;
            log = Files.createTempFile(workspace.resolve("target"), "online-revocation-shell-", ".log");
            process = new ProcessBuilder(shellTest.toString(), "sync::maintenance_probe::production_session_and_sync_loops",
                    "--exact", "--ignored", "--nocapture").directory(workspace.toFile()).redirectError(log.toFile()).start();
            output = process.getOutputStream();
            input = process.inputReader(StandardCharsets.UTF_8);
            try {
                send(Map.of("base", "http://127.0.0.1:" + port, "tokens", tokens, "activity", joined, "group", group));
                expect("READY", 30);
            } catch (Exception | AssertionError failure) {
                close();
                throw failure;
            }
        }

        void send(Object value) throws IOException { DesktopBackgroundMaintenanceIntegrationTest.this.send(output, json, value); }
        void expect(String signal, long seconds) throws Exception { DesktopBackgroundMaintenanceIntegrationTest.this.expect(process, input, signal, seconds); }

        void stop(String password) throws Exception {
            send(Map.of("stop", true));
            expect("STOPPED", 5);
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as("Online background diagnostics: %s", log).isZero();
            assertThat(Files.readString(log)).doesNotContain(tokens.accessToken(), tokens.refreshToken(), password);
        }

        public void close() throws Exception {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("Online probe cleanup: %s", log).isTrue();
            }
            output.close();
            input.close();
        }
    }

    private void runBackgroundLoops(boolean realTime, Outage outageMode) throws Exception {
        boolean outage = outageMode != Outage.NONE;
        boolean revokedOffline = outageMode == Outage.REVOKED;
        boolean expiredOffline = outageMode == Outage.LONG;
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
            var joined = AuthenticatedActivities.join(context, user, room.id(), "background-loop", null, "background-probe");
            var json = context.getBean(ObjectMapper.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            var sync = context.getBean(SyncService.class);
            var log = Files.createTempFile(workspace.resolve("target"), "background-shell-", ".log");
            var process = new ProcessBuilder(shellTest.toString(), "sync::maintenance_probe::production_session_and_sync_loops",
                    "--exact", "--ignored", "--nocapture").directory(workspace.toFile()).redirectError(log.toFile()).start();
            int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            try (var link = new TcpTestLink(port); var output = process.getOutputStream(); var input = process.inputReader(StandardCharsets.UTF_8)) {
                send(output, json, Map.of("base", "http://127.0.0.1:" + (outage ? link.port() : port), "tokens", tokens,
                        "activity", joined, "group", group.id()));
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
                    if (outage) {
                        link.disconnect();
                        send(output, json, Map.of("offline", true));
                        expect(process, input, "OFFLINE", 22);
                        long refusalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                        while (link.rejectedConnections() == 0 && System.nanoTime() < refusalDeadline) Thread.sleep(20);
                        assertThat(link.rejectedConnections()).as("Production reconnect attempted while link is offline").isPositive();
                        if (revokedOffline) {
                            assertThat(context.getBean(ActivityCoordinator.class).leave(user, joined.activityId(), joined.deviceId(), joined.leaseId())).isTrue();
                        }
                        if (expiredOffline) {
                            send(output, json, Map.of("expiredOffline", true));
                            expect(process, input, "EXPIRED", 85);
                            long releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
                            while (jdbc.queryForObject("SELECT count(*) FROM account_activity WHERE user_id=?", Integer.class, user) != 0
                                    && System.nanoTime() < releaseDeadline) Thread.sleep(50);
                            assertThat(jdbc.queryForObject("SELECT count(*) FROM account_activity WHERE user_id=?", Integer.class, user)).isZero();
                            assertThat(jdbc.queryForObject("SELECT count(*) FROM ip_allocations WHERE activity_id=? AND user_id=? AND released_at IS NULL",
                                    Integer.class, joined.activityId(), user)).isZero();
                        } else Thread.sleep(6000);
                        var missed = context.getBean(MessageService.class).post(user, group.id(), "offline-background-message");
                        long restored = System.nanoTime();
                        link.restore();
                        send(output, json, Map.of("messageId", missed.id(), "expectRevoked", revokedOffline || expiredOffline,
                                "expectConnected", true));
                        expect(process, input, "MESSAGE", 15);
                        send(output, json, Map.of("serviceRestored", true));
                        expect(process, input, "ONLINE", 17);
                        if (revokedOffline || expiredOffline) {
                            send(output, json, Map.of("activityRevoked", true));
                            expect(process, input, "REVOKED", 17);
                            assertThat(jdbc.queryForObject("SELECT count(*) FROM connection_leases WHERE id=? AND revoked_at IS NOT NULL",
                                    Integer.class, joined.leaseId())).isOne();
                            assertThat(jdbc.queryForObject("SELECT count(*) FROM account_activity WHERE user_id=?", Integer.class, user)).isZero();
                            assertThat(jdbc.queryForObject("SELECT count(*) FROM ip_allocations WHERE activity_id=? AND user_id=? AND released_at IS NULL",
                                    Integer.class, joined.activityId(), user)).isZero();
                            if (expiredOffline) {
                                assertThat(jdbc.queryForObject("SELECT state FROM activity_members WHERE activity_id=? AND user_id=?",
                                        String.class, joined.activityId(), user)).isIn("REVOKED", "LEFT");
                                assertThat(jdbc.queryForObject("SELECT count(*) FROM connection_leases WHERE activity_id=? AND user_id=?",
                                        Integer.class, joined.activityId(), user)).isOne();
                            }
                            long recovery = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - restored);
                            assertThat(recovery).as("Recovery after reconnecting the test link").isLessThanOrEqualTo(15000);
                            send(output, json, Map.of("stop", true));
                            expect(process, input, "STOPPED", 5);
                            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
                            assertThat(process.exitValue()).as("Background shell diagnostics: %s", log).isZero();
                            assertThat(Files.readString(log)).doesNotContain(tokens.accessToken(), tokens.refreshToken(), password);
                            System.out.println("BACKGROUND_OFFLINE_" + outageMode + "_RECOVERY_MS " + recovery);
                            return;
                        }
                        checkLease.run();
                        long recovery = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - restored);
                        assertThat(recovery).as("Recovery after reconnecting the test link").isLessThanOrEqualTo(15000);
                        System.out.println("BACKGROUND_OUTAGE_RECOVERY_MS " + recovery);
                        break;
                    }
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
                if (!outage) assertThat(rotatedMessages).as("Messages received after automatic credential rotation").isPositive();
                if (realTime) {
                    assertThat(messages).isGreaterThanOrEqualTo(60);
                    assertThat(Instant.now()).isAfter(tokens.accessExpiresAt().plusSeconds(15));
                }
                assertThat(repository.findSessionById(tokens.sessionId()).orElseThrow().generation()).isEqualTo(outage ? 1L : 2L);
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
