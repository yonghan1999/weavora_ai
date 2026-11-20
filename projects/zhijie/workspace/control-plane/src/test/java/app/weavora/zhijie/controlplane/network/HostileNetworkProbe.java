package app.weavora.zhijie.controlplane.network;

import static org.assertj.core.api.Assertions.*;

import app.weavora.zhijie.controlplane.activity.NetworkProbeRevocation;
import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import app.weavora.zhijie.controlplane.grant.GrantService;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.room.RoomService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

public final class HostileNetworkProbe {
    private HostileNetworkProbe() {}

    static void run(ServletWebServerApplicationContext context, String mode) throws Exception {
        run(context, mode, NetworkProbeRevocation.LEAVE);
    }

    public static void run(ServletWebServerApplicationContext context, String mode, NetworkProbeRevocation action) throws Exception {
        var policies = context.getBean(NetworkPolicyService.class);
        var originalMode = policies.current().mode();
        policies.update("hostile-direct-test", NetworkPolicyMode.DIRECT_ONLY, policies.current().revision());
        var tokens = List.of(NetworkProbeRevocation.login(context, "receiver"), NetworkProbeRevocation.login(context, "hostile"));
        var users = tokens.stream().map(AuthTokens::userId).toList();
        var groups = context.getBean(GroupService.class);
        var group = groups.createGroup(users.getFirst(), "持续旧端发包隔离", null, "");
        Process process = null;
        try {
            groups.acceptInvitation(users.get(1), groups.invite(users.getFirst(), group.id(), users.get(1)).id());
            var rooms = context.getBean(RoomService.class);
            var room = rooms.createRoom(users.getFirst(), group.id(), "真实失权边界", "", "", null);
            var activity = rooms.startActivity(users.getFirst(), room.id());
            var coordinator = context.getBean(ActivityCoordinator.class);
            var receiver = coordinator.join(NetworkProbeRevocation.session(context, tokens.getFirst()), room.id(), null, "hostile-receiver");
            var hostile = coordinator.join(NetworkProbeRevocation.session(context, tokens.get(1)), room.id(), null, "hostile-sender");
            var directory = Files.createTempDirectory(Path.of("target"), "network-hostile-").toAbsolutePath();
            var publicFile = directory.resolve("public.json");
            var output = directory.resolve("rust-network.log");
            var builder = new ProcessBuilder("cargo", "test", "--locked", "--offline", "-p", "zhijie-helper", "--lib",
                    "system::full_room_tests::hostile_control_plane::revoked_direct_endpoint_keeps_sending_but_cannot_deliver",
                    "--", "--ignored", "--exact", "--nocapture")
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("ZHIJIE_TEST_CONTROL_PLANE_URL", "http://127.0.0.1:" + context.getWebServer().getPort());
            builder.environment().put("ZHIJIE_TEST_HOSTILE_MODE", mode);
            builder.environment().put("ZHIJIE_TEST_HOSTILE_DIRECTORY", directory.toString());
            process = builder.start();
            for (int attempt = 0; attempt < 150 && process.isAlive() && !Files.exists(publicFile); attempt++) Thread.sleep(100);
            assertThat(Files.exists(publicFile)).as("Hostile endpoints ready; inspect %s", output).isTrue();
            var json = context.getBean(ObjectMapper.class);
            var publicData = json.readTree(Files.readString(publicFile));
            assertThat(publicData.size()).isEqualTo(2);
            var grants = context.getBean(GrantService.class);
            var peers = context.getBean(NetworkPeerService.class);
            var joins = List.of(receiver, hostile);
            var starts = new ArrayList<Map<String, Object>>();
            for (int index = 0; index < 2; index++) {
                var joined = joins.get(index);
                var grant = grants.network(NetworkProbeRevocation.session(context, tokens.get(index)), activity.id(), publicData.get(index).path("publicKey").asText());
                peers.register(users.get(index), activity.id(), grant.networkTicket(), publicData.get(index).path("endpoint").asText());
                byte[] privateKey = new byte[32]; Arrays.fill(privateKey, (byte)(index + 1));
                var start = new HashMap<String, Object>();
                start.put("protocolVersion", 1); start.put("requestId", "hostile-" + index);
                start.put("ticket", grant.networkTicket()); start.put("activityId", activity.id());
                start.put("activityGeneration", joined.activityGeneration()); start.put("memberGeneration", joined.memberGeneration());
                start.put("deviceId", joined.deviceId()); start.put("leaseId", joined.leaseId());
                start.put("policyRevision", grant.policyRevision()); start.put("allowedPaths", grant.allowedPaths());
                start.put("privateKey", Base64.getEncoder().encodeToString(privateKey));
                start.put("virtualIpv4", grant.virtualIpv4()); start.put("activityCidr", grant.activityCidr());
                start.put("expiresAt", grant.expiresAt().toString()); starts.add(start);
            }
            var rotated = NetworkProbeRevocation.refresh(context, tokens.get(1));
            context.getBean(NetworkAccessService.class).validate((String)starts.get(1).get("ticket"));
            try (var input = process.getOutputStream()) {
                input.write(json.writeValueAsBytes(starts)); input.write('\n'); input.flush();
            }
            var ready = directory.resolve("ready");
            for (int attempt = 0; attempt < 100 && process.isAlive() && !Files.exists(ready); attempt++) Thread.sleep(10);
            assertThat(Files.exists(ready)).as("Actual bidirectional encrypted traffic; inspect %s", output).isTrue();
            for (var start : starts) assertThat(Instant.parse((String)start.get("expiresAt"))).isAfter(Instant.now().plusSeconds(10));
            action.revoke(context, tokens.get(1), rotated, activity.id(), hostile.leaseId());
            long confirmed = System.nanoTime();
            Files.writeString(directory.resolve("revoked"), "confirmed");
            var isolated = directory.resolve("isolated");
            while (process.isAlive() && !Files.exists(isolated) && System.nanoTime() - confirmed < TimeUnit.SECONDS.toNanos(5)) Thread.sleep(10);
            assertThat(Files.exists(isolated)).as("Forced isolation within five seconds; inspect %s", output).isTrue();
            assertThat(System.nanoTime() - confirmed).isLessThan(TimeUnit.SECONDS.toNanos(5));
            long isolatedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - confirmed);
            assertThat(context.getBean(NetworkAccessService.class).validate((String)starts.getFirst().get("ticket"))).isNotNull();
            assertThatThrownBy(() -> context.getBean(NetworkAccessService.class).validate((String)starts.get(1).get("ticket")))
                    .isInstanceOf(app.weavora.zhijie.controlplane.common.ApiException.class);
            NetworkProbeRevocation.assertPersisted(context, users.get(1), activity.id(), hostile.leaseId(), confirmed);
            for (var start : starts) assertThat(Instant.parse((String)start.get("expiresAt"))).isAfter(Instant.now());
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("Hostile probe cleanup; inspect %s", output).isTrue();
            assertThat(process.exitValue()).as("Hostile peer isolation; inspect %s", output).isZero();
            assertThat(Files.readString(output)).contains("REAL_HOSTILE_DIRECT_ISOLATED mode=" + mode,
                    "old_endpoint_stopped=false", "fresh_handshake_sent=true delivered=0");
            String logs = Files.readString(output);
            for (int index = 0; index < tokens.size(); index++) {
                assertThat(logs).doesNotContain(tokens.get(index).accessToken(), tokens.get(index).refreshToken(),
                        (String)starts.get(index).get("ticket"), (String)starts.get(index).get("privateKey"));
            }
            assertThat(logs).doesNotContain(rotated.accessToken(), rotated.refreshToken());
            System.out.println("REAL_AUTH_DIRECT_REVOKED action=" + action + " mode=" + mode + " observed_ms="
                    + isolatedMillis + " directory=" + directory);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            groups.dissolveGroup(users.getFirst(), group.id());
            policies.update("hostile-direct-test-restore", originalMode, policies.current().revision());
        }
    }
}
