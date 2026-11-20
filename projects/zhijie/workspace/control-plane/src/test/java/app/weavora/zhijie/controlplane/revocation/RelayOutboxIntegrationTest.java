package app.weavora.zhijie.controlplane.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import app.weavora.zhijie.controlplane.grant.GrantService;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.room.RoomService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class RelayOutboxIntegrationTest {
    @Test void actualRustRelayReceivesOutboxRevocationAndLateReplayDoesNotDisconnectRejoinedMember() throws Exception {
        var workspace = Path.of(System.getProperty("user.dir")).getParent();
        var directory = Files.createTempDirectory(workspace.resolve("target"), "relay-outbox-");
        var build = new ProcessBuilder("cargo", "test", "--locked", "--offline", "-p", "zhijie-relay",
                "--test", "revocation", "--no-run").directory(workspace.toFile())
                .redirectErrorStream(true).redirectOutput(directory.resolve("build.log").toFile()).start();
        try {
            assertThat(build.waitFor(180, TimeUnit.SECONDS)).as("Rust build: %s", directory).isTrue();
            assertThat(build.exitValue()).as("Rust build: %s", directory).isZero();
        } finally { if (build.isAlive()) { build.descendants().forEach(ProcessHandle::destroyForcibly); build.destroyForcibly(); } }
        String validationKey = UUID.randomUUID().toString();
        String revocationKey = UUID.randomUUID().toString();
        int managementPort;
        try (var reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { managementPort = reservation.getLocalPort(); }
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--zhijie.relay.management-key=" + validationKey,
                "--zhijie.relay.revocation-enabled=true", "--zhijie.relay.revocation-key=" + revocationKey,
                "--zhijie.relay.admin-url=http://127.0.0.1:" + managementPort)) {
            String owner = UUID.randomUUID().toString();
            String member = UUID.randomUUID().toString();
            for (String user : java.util.List.of(owner, member)) {
                context.getBean(IdentityRepository.class).insertUser(new UserAccount(user, user + "@example.test",
                        AccountStatus.ACTIVE, "unused", Instant.now()));
            }
            var groups = context.getBean(GroupService.class);
            var group = groups.createGroup(owner, "真实 Relay Outbox", null, "");
            groups.acceptInvitation(member, groups.invite(owner, group.id(), member).id());
            var rooms = context.getBean(RoomService.class);
            var room = rooms.createRoom(owner, group.id(), "撤销组合", "", "", null);
            String activity = rooms.startActivity(owner, room.id()).id();
            var coordinator = context.getBean(ActivityCoordinator.class);
            coordinator.join(owner, room.id(), "owner", null, "owner");
            var joined = coordinator.join(member, room.id(), "member", null, "member");
            var grants = context.getBean(GrantService.class);
            byte[] publicKey = new byte[32];
            publicKey[0] = 1;
            var first = grants.network(owner, activity, "owner", Base64.getEncoder().encodeToString(publicKey));
            publicKey[0] = 2;
            String memberKey = Base64.getEncoder().encodeToString(publicKey);
            var second = grants.network(member, activity, "member", memberKey);
            int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var process = new ProcessBuilder("cargo", "test", "--locked", "--offline", "-p", "zhijie-relay",
                    "--test", "revocation", "--", "--ignored", "--nocapture")
                    .directory(workspace.toFile()).redirectError(directory.resolve("rust.log").toFile()).start();
            var transcript = new StringBuilder();
            var json = context.getBean(ObjectMapper.class);
            try (var output = process.outputWriter(StandardCharsets.UTF_8); var input = process.inputReader(StandardCharsets.UTF_8)) {
                send(output, json.writeValueAsString(Map.of("controlPlaneUrl", "http://127.0.0.1:" + port,
                        "validationKey", validationKey, "revocationKey", revocationKey,
                        "managementAddress", "127.0.0.1:" + managementPort, "ticketA", first.networkTicket(),
                        "ticketB", second.networkTicket(), "ipA", first.virtualIpv4(), "ipB", second.virtualIpv4())));
                expect(input, transcript, "RELAY_OUTBOX_READY");
                var dispatcher = context.getBean(RevocationDispatcher.class);
                dispatcher.dispatchPending();
                coordinator.leave(member, activity, joined.deviceId(), joined.leaseId());
                dispatcher.dispatchPending();
                var sql = context.getBean(JdbcTemplate.class);
                String eventId = sql.queryForObject("SELECT event_id FROM outbox_events WHERE type='network.revoked'", String.class);
                assertThat(sql.queryForObject("SELECT delivered_at IS NOT NULL FROM outbox_events WHERE event_id=?", Boolean.class, eventId)).isTrue();
                assertThat(sql.queryForObject("SELECT delivered_at IS NULL FROM outbox_events WHERE type='voice.revoked'", Boolean.class)).isTrue();
                expect(input, transcript, "RELAY_OUTBOX_REVOKED");
                var replacement = coordinator.join(member, room.id(), "member-new", null, "rejoin");
                assertThat(replacement.memberGeneration()).isGreaterThan(joined.memberGeneration());
                var fresh = grants.network(member, activity, "member-new", memberKey);
                assertThat(fresh.virtualIpv4()).isEqualTo(second.virtualIpv4());
                send(output, json.writeValueAsString(Map.of("ticketB", fresh.networkTicket())));
                expect(input, transcript, "RELAY_OUTBOX_NEW_GENERATION_READY");
                sql.update("UPDATE outbox_events SET delivered_at=NULL,external_next_attempt_at=NULL WHERE event_id=?", eventId);
                dispatcher.dispatchPending();
                assertThat(sql.queryForObject("SELECT delivered_at IS NOT NULL FROM outbox_events WHERE event_id=?", Boolean.class, eventId)).isTrue();
                send(output, "LATE_EVENT_APPLIED");
                expect(input, transcript, "RELAY_OUTBOX_LATE_EVENT_ISOLATED_OK");
                assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).as("Rust result: %s", directory).isZero();
                assertThat(Files.readString(directory.resolve("rust.log")) + transcript).doesNotContain(
                        validationKey, revocationKey, first.networkTicket(), second.networkTicket(), fresh.networkTicket());
                Files.writeString(directory.resolve("scenario.log"), transcript);
            } finally { if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); } }
            System.out.println("RELAY_OUTBOX_EVIDENCE " + directory);
        }
    }

    private void send(BufferedWriter output, String line) throws Exception { output.write(line); output.newLine(); output.flush(); }

    private void expect(BufferedReader input, StringBuilder transcript, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (!input.ready()) { Thread.sleep(10); continue; }
            String line = input.readLine();
            assertThat(line).as("Rust closed before %s", expected).isNotNull();
            transcript.append(line).append('\n');
            if (line.equals(expected)) return;
        }
        throw new AssertionError("Rust signal deadline: " + expected);
    }
}
