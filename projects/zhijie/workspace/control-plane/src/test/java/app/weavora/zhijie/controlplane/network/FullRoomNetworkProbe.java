package app.weavora.zhijie.controlplane.network;

import static org.assertj.core.api.Assertions.*;

import app.weavora.zhijie.controlplane.activity.*;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.*;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.room.RoomService;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 共用真实控制面十二端网络探针；不创建系统 TUN。 */
public final class FullRoomNetworkProbe {
    private FullRoomNetworkProbe() {}
    public static void run(ServletWebServerApplicationContext context, String relayManagementKey, NetworkPolicyMode mode, List<String> sharedUsers, List<JoinResult> sharedJoins, String sharedActivity, java.nio.file.Path voiceReady) throws Exception {
        var identities = context.getBean(IdentityRepository.class);
        var groups = context.getBean(GroupService.class);
        var rooms = context.getBean(RoomService.class);
        var coordinator = context.getBean(ActivityCoordinator.class);
        var grants = context.getBean(GrantService.class);
        var peers = context.getBean(NetworkPeerService.class);
        var policies = context.getBean(NetworkPolicyService.class);
        var originalMode = policies.current().mode();
        policies.update("full-room-test", mode, policies.current().revision());
        var users = sharedUsers == null ? new ArrayList<String>() : new ArrayList<>(sharedUsers);
        for (int index = 0; sharedUsers == null && index < 13; index++) {
            String user = UUID.randomUUID().toString();
            identities.insertUser(new UserAccount(user, user + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
            users.add(user);
        }
        String owner = users.getFirst();
        var group = sharedUsers == null ? groups.createGroup(owner, "真实十二端网络", null, "") : null;
        Process process = null;
        try {
            String activity = sharedActivity;
            var joins = sharedJoins == null ? new ArrayList<JoinResult>() : new ArrayList<>(sharedJoins);
            if (sharedUsers == null) {
                for (String user : users.subList(1, users.size())) groups.acceptInvitation(user, groups.invite(owner, group.id(), user).id());
                var room = rooms.createRoom(owner, group.id(), "实际数据泵", "", "", null);
                activity = rooms.startActivity(owner, room.id()).id();
                for (int index = 0; index < 12; index++) joins.add(coordinator.join(users.get(index), room.id(), "device-" + index, null, "real-full-" + index));
                assertThatThrownBy(() -> coordinator.join(users.get(12), room.id(), "device-13", null, "real-full-13"))
                        .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CAPACITY_FULL");
            }
            var directory = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"), "network-room-").toAbsolutePath();
            var publicFile = directory.resolve("public.json");
            var ready = directory.resolve("ready");
            var renewReady = directory.resolve("renew-ready");
            var policyReady = directory.resolve("policy-ready");
            var output = directory.resolve("rust-network.log");
            var builder = new ProcessBuilder("cargo", "test", "--offline", "-p", "zhijie-helper", "--lib",
                    "system::full_room_tests::control_plane::twelve_real_control_plane_data_pumps", "--", "--ignored", "--exact", "--nocapture")
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            String entry = "http://127.0.0.1:" + ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            builder.environment().put("ZHIJIE_TEST_CONTROL_PLANE_URL", entry);
            builder.environment().put("ZHIJIE_TEST_RELAY_MANAGEMENT_KEY", relayManagementKey);
            String pathMode = switch (mode) {
                case DIRECT_ONLY -> "direct";
                case RELAY_ONLY -> "relay";
                case DIRECT_WITH_RELAY_FALLBACK -> "mixed";
            };
            builder.environment().put("ZHIJIE_TEST_NETWORK_MODE", pathMode);
            builder.environment().put("ZHIJIE_TEST_NETWORK_PUBLIC", publicFile.toString());
            builder.environment().put("ZHIJIE_TEST_NETWORK_READY", ready.toString());
            builder.environment().put("ZHIJIE_TEST_NETWORK_POLICY_READY", policyReady.toString());
            builder.environment().put("ZHIJIE_TEST_NETWORK_RENEW_READY", renewReady.toString());
            process = builder.start();
            for (int attempt = 0; attempt < 150 && process.isAlive() && !java.nio.file.Files.exists(publicFile); attempt++) Thread.sleep(100);
            assertThat(java.nio.file.Files.exists(publicFile)).as("Public endpoints ready; inspect %s", output).isTrue();
            var json = context.getBean(ObjectMapper.class);
            var publicData = json.readTree(java.nio.file.Files.readString(publicFile));
            assertThat(publicData.size()).isEqualTo(12);
            var starts = new ArrayList<Map<String, Object>>();
            for (int index = 0; index < 12; index++) {
                var grant = grants.network(users.get(index), activity, joins.get(index).deviceId(), publicData.get(index).path("publicKey").asText());
                peers.register(users.get(index), activity, grant.networkTicket(), mode != NetworkPolicyMode.RELAY_ONLY
                        ? publicData.get(index).path("endpoint").asText() : null);
                var start = new HashMap<String, Object>();
                start.put("protocolVersion", 1); start.put("requestId", "real-full-" + index);
                start.put("ticket", grant.networkTicket()); start.put("activityId", activity);
                start.put("activityGeneration", joins.get(index).activityGeneration()); start.put("memberGeneration", grant.memberGeneration());
                start.put("deviceId", joins.get(index).deviceId()); start.put("leaseId", grant.leaseId());
                start.put("policyRevision", grant.policyRevision()); start.put("allowedPaths", grant.allowedPaths());
                start.put("privateKey", key(index + 1)); start.put("virtualIpv4", grant.virtualIpv4());
                start.put("activityCidr", grant.activityCidr()); start.put("expiresAt", grant.expiresAt().toString());
                starts.add(start);
            }
            if (voiceReady != null) {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while (!java.nio.file.Files.exists(voiceReady) && System.nanoTime() < deadline) Thread.sleep(20);
                assertThat(java.nio.file.Files.exists(voiceReady)).as("Voice observation started").isTrue();
            }
            var input = process.getOutputStream();
            input.write(json.writeValueAsBytes(starts)); input.write('\n'); input.flush();
            var originalTickets = starts.stream().map(start -> (String)start.get("ticket")).toList();
            var originalExpiry = starts.stream().map(start -> Instant.parse((String)start.get("expiresAt"))).max(Instant::compareTo).orElseThrow();
            long nextRenewal = System.nanoTime();
            for (int round = 1; round <= 5; round++) {
                String expectedRound = Integer.toString(round);
                for (int attempt = 0; attempt < 250 && process.isAlive(); attempt++) {
                    if (java.nio.file.Files.exists(renewReady) && expectedRound.equals(java.nio.file.Files.readString(renewReady))) break;
                    Thread.sleep(100);
                }
                assertThat(java.nio.file.Files.readString(renewReady)).as("Twelve-end transfer before renewal round %s; inspect %s", round, output).isEqualTo(expectedRound);
                nextRenewal += java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                while (process.isAlive() && System.nanoTime() < nextRenewal) Thread.sleep(100);
                assertThat(process.isAlive()).as("Pumps alive before renewal round %s; inspect %s", round, output).isTrue();
                for (int index = 0; index < 12; index++) {
                    var joined = joins.get(index);
                    coordinator.heartbeat(users.get(index), joined.activityId(), joined.deviceId(), joined.leaseId(), joined.activityGeneration(), joined.memberGeneration());
                    var grant = grants.network(users.get(index), activity, joins.get(index).deviceId(), publicData.get(index).path("publicKey").asText());
                    assertThat(grant.leaseId()).isEqualTo(starts.get(index).get("leaseId"));
                    assertThat(grant.virtualIpv4()).isEqualTo(starts.get(index).get("virtualIpv4"));
                    assertThat(grant.expiresAt()).isAfter(Instant.parse((String)starts.get(index).get("expiresAt")));
                    peers.register(users.get(index), activity, grant.networkTicket(), mode != NetworkPolicyMode.RELAY_ONLY
                            ? publicData.get(index).path("endpoint").asText() : null);
                    starts.get(index).put("ticket", grant.networkTicket());
                    starts.get(index).put("expiresAt", grant.expiresAt().toString());
                }
                input.write(json.writeValueAsBytes(starts)); input.write('\n'); input.flush();
            }
            input.close();
            assertThat(Instant.now()).isAfter(originalExpiry);
            for (var ticket : originalTickets) {
                assertThatThrownBy(() -> context.getBean(NetworkAccessService.class).validate(ticket)).isInstanceOf(ApiException.class);
            }
            for (int attempt = 0; attempt < 250 && process.isAlive() && !java.nio.file.Files.exists(ready); attempt++) Thread.sleep(100);
            assertThat(java.nio.file.Files.exists(ready)).as("132 directions delivered; inspect %s", output).isTrue();
            if (sharedUsers == null) coordinator.leave(users.get(11), activity, joins.get(11).deviceId(), joins.get(11).leaseId());
            else {
                // 单独撤销网络票据，语音仍使用相同成员与租约。
                var grant = context.getBean(GrantRepository.class).findByTokenHash(context.getBean(CredentialProtector.class).hashToken((String)starts.get(11).get("ticket"))).orElseThrow();
                context.getBean(GrantRepository.class).revoke(grant.id(), Instant.now());
            }
            for (int attempt = 0; attempt < 120 && process.isAlive() && !java.nio.file.Files.exists(policyReady); attempt++) Thread.sleep(100);
            assertThat(java.nio.file.Files.exists(policyReady)).as("Surviving eleven delivered; inspect %s", output).isTrue();
            policies.update("full-room-policy-revoke", mode == NetworkPolicyMode.DIRECT_ONLY
                    ? NetworkPolicyMode.RELAY_ONLY : NetworkPolicyMode.DIRECT_ONLY, policies.current().revision());
            for (var start : starts) {
                assertThatThrownBy(() -> context.getBean(NetworkAccessService.class).validate((String)start.get("ticket")))
                        .isInstanceOf(ApiException.class);
            }
            assertThat(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).as("Revocation and survivor traffic deadline").isTrue();
            assertThat(process.exitValue()).as("Real full-room data plane; inspect %s", output).isZero();
            String evidence = java.nio.file.Files.readString(output);
            assertThat(evidence).contains("members=12 directions=132 packets_per_direction=10", "members=11 directions=110 packets_per_direction=10");
            assertThat(evidence).contains("REAL_NETWORK_POLICY_REVOKED mode=" + pathMode + " stopped=11 deadline_seconds=5");
            assertThat(evidence).contains("REAL_NETWORK_RENEWED mode=" + pathMode + " members=12 preserved_keys_ip_lease=true");
            assertThat(evidence).contains("phase=2 members=12 directions=132 packets_per_direction=10");
            assertThat(evidence).contains("REAL_NETWORK_ORIGINAL_EXPIRED mode=" + pathMode + " members=12 renewal_rounds=5 all_directions_delivered=true");
            assertThat(evidence.lines().filter(line -> line.startsWith("REAL_NETWORK_RENEWED ")).count()).isEqualTo(5);
            if (mode == NetworkPolicyMode.DIRECT_WITH_RELAY_FALLBACK) {
                assertThat(evidence).contains("REAL_NETWORK_RELAY_STOPPED survivors=11 direct_continues=true");
            }
        } finally {
            if (process != null && process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            if (group != null) groups.dissolveGroup(owner, group.id());
            policies.update("full-room-test-restore", originalMode, policies.current().revision());
        }
    }

    private static String key(int value) {
        byte[] bytes = new byte[32]; Arrays.fill(bytes, (byte)value);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
