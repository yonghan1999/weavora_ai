package app.weavora.zhijie.controlplane.network;

import static org.assertj.core.api.Assertions.*;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.*;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.*;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.room.RoomService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.net.URI;
import java.net.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;

class NetworkPeerIntegrationTest {
    private static EmbeddedPostgres postgres;
    private static ConfigurableApplicationContext context;
    private static final String relayManagementKey = UUID.randomUUID().toString();

    @BeforeAll static void start() throws Exception {
        if ("true".equals(System.getenv("ZHIJIE_TEST_RELAY_MEDIA"))) {
            var build = new ProcessBuilder("cargo", "test", "--offline", "-p", "zhijie-relay", "--test", "control_plane", "--no-run")
                    .redirectErrorStream(true).redirectOutput(java.nio.file.Path.of("target/relay-build.log").toFile()).start();
            try {
                assertThat(build.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(build.exitValue()).isZero();
            } finally { if (build.isAlive()) { build.descendants().forEach(ProcessHandle::destroyForcibly); build.destroyForcibly(); } }
            var helperBuild = new ProcessBuilder("cargo", "test", "--offline", "-p", "zhijie-helper", "--lib", "--no-run")
                    .redirectErrorStream(true).redirectOutput(java.nio.file.Path.of("target/network-full-room-helper-build.log").toFile()).start();
            try {
                assertThat(helperBuild.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(helperBuild.exitValue()).isZero();
            } finally { if (helperBuild.isAlive()) { helperBuild.descendants().forEach(ProcessHandle::destroyForcibly); helperBuild.destroyForcibly(); } }
        }
        postgres = EmbeddedPostgres.start();
        var app = new SpringApplication(ControlPlaneApplication.class);
        context = app.run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=", "--zhijie.scheduling.enabled=false",
                "--zhijie.relay.management-key=" + relayManagementKey);
    }

    @AfterAll static void stop() throws Exception {
        if (context != null) context.close();
        if (postgres != null) postgres.close();
    }

    @Test void peersAreScopedToCurrentActivityDeviceLeaseAndPolicyAndDisappearOnRevocation() throws Exception {
        var identities = context.getBean(IdentityRepository.class);
        var users = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            String user = UUID.randomUUID().toString(); users.add(user);
            identities.insertUser(new UserAccount(user, user + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
        }
        String a = users.get(0), b = users.get(1), c = users.get(2);
        var groups = context.getBean(GroupService.class);
        var group = groups.createGroup(a, "Peer 隔离", null, "");
        for (String user : List.of(b, c)) groups.acceptInvitation(user, groups.invite(a, group.id(), user).id());
        var rooms = context.getBean(RoomService.class);
        var room = rooms.createRoom(a, group.id(), "同房", "", "", null);
        var otherRoom = rooms.createRoom(a, group.id(), "隔离房", "", "", null);
        String activity = rooms.startActivity(a, room.id()).id();
        String other = rooms.startActivity(a, otherRoom.id()).id();
        var coordinator = context.getBean(ActivityCoordinator.class);
        AuthenticatedActivities.join(context, a, room.id(), "device-a", null, "a");
        var joinedB = AuthenticatedActivities.join(context, b, room.id(), "device-b", null, "b");
        AuthenticatedActivities.join(context, c, otherRoom.id(), "device-c", null, "c");
        var grants = context.getBean(GrantService.class);
        var peers = context.getBean(NetworkPeerService.class);
        var access = context.getBean(NetworkAccessService.class);
        var ga = grants.network(a, activity, "device-a", key(1));
        var gb = grants.network(b, activity, joinedB.deviceId(), key(2));
        var gc = grants.network(c, other, "device-c", key(3));
        var validation = context.getBean(NetworkAccessController.class).validate(new NetworkAccessController.AccessBody(ga.networkTicket()));
        assertThat(validation.expiresAt()).isBeforeOrEqualTo(ga.expiresAt());
        var http = HttpClient.newHttpClient();
        String entry = "http://127.0.0.1:" + ((ServletWebServerApplicationContext)context).getWebServer().getPort();
        var json = context.getBean(ObjectMapper.class);
        var httpRequest = HttpRequest.newBuilder(URI.create(entry + "/api/v1/network-access/validate")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("ticket", ga.networkTicket())))).build();
        var httpResponse = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(httpResponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(httpResponse.body()).path("publicKey").asText()).isEqualTo(key(1));
        assertThat(httpResponse.body()).doesNotContain(ga.networkTicket(), "privateKey");
        assertThat(peers.register(a, activity, ga.networkTicket(), "127.0.0.1:41001")).isEmpty();
        assertThat(peers.register(b, activity, gb.networkTicket(), "127.0.0.1:41002")).extracting(NetworkPeerService.Peer::userId).containsExactly(a);
        assertThat(peers.register(c, other, gc.networkTicket(), "127.0.0.1:41003")).isEmpty();
        assertThat(peers.list(a, activity, ga.networkTicket())).singleElement().satisfies(peer -> {
            assertThat(peer.userId()).isEqualTo(b); assertThat(peer.publicKey()).isEqualTo(key(2));
            assertThat(peer.virtualIpv4()).isEqualTo(gb.virtualIpv4()); assertThat(peer.leaseId()).isEqualTo(joinedB.leaseId());
            assertThat(peer.memberGeneration()).isEqualTo(joinedB.memberGeneration());
        });
        var activityRecords = context.getBean(ActivityRepository.class);
        var originalLease = activityRecords.findLease(joinedB.leaseId()).orElseThrow();
        var shortExpiry = Instant.now().plusSeconds(20);
        activityRecords.updateLease(new ConnectionLease(originalLease.id(), originalLease.activityId(), originalLease.userId(), originalLease.deviceId(),
                originalLease.activityGeneration(), originalLease.memberGeneration(), originalLease.policyRevision(), originalLease.voiceState(),
                originalLease.networkState(), shortExpiry, null));
        try {
            var boundedResponse = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(boundedResponse.statusCode()).isEqualTo(200);
            var peer = json.readTree(boundedResponse.body()).path("peers").get(0);
            assertThat(peer.path("userId").asText()).isEqualTo(b);
            assertThat(peer.path("leaseId").asText()).isEqualTo(joinedB.leaseId());
            assertThat(peer.path("memberGeneration").asLong()).isEqualTo(joinedB.memberGeneration());
            assertThat(java.time.Duration.between(Instant.parse(peer.path("expiresAt").asText()), shortExpiry).abs()).isLessThan(java.time.Duration.ofMillis(1));
        } finally { activityRecords.updateLease(originalLease); }
        assertThatThrownBy(() -> peers.list(a, activity, gb.networkTicket())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> peers.list(c, activity, gc.networkTicket())).isInstanceOf(ApiException.class);
        for (String endpoint : List.of("example.test:41001", "0.0.0.0:4", "224.0.0.1:4", "127.0.0.1:0", "127.0.0.1:65536", "255.255.255.255:4"))
            assertThatThrownBy(() -> peers.register(a, activity, ga.networkTicket(), endpoint)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> grants.network(a, activity, "device-a", "invalid-key")).isInstanceOf(ApiException.class);
        var renewed = grants.network(b, activity, joinedB.deviceId(), key(2).replace("=", ""));
        assertThat(access.validate(renewed.networkTicket()).grant().publicKey()).isEqualTo(key(2));
        assertThat(peers.list(a, activity, ga.networkTicket())).singleElement().extracting(NetworkPeerService.Peer::publicKey).isEqualTo(key(2));
        var rotated = grants.network(b, activity, joinedB.deviceId(), key(5));
        assertThat(rotated.leaseId()).isEqualTo(gb.leaseId());
        var grantRecords = context.getBean(GrantRepository.class);
        for (var previous : List.of(gb, renewed)) {
            assertThat(grantRecords.findById(previous.grantId()).orElseThrow().revokedAt()).isNotNull();
        }
        assertThat(peers.list(a, activity, ga.networkTicket())).isEmpty();
        peers.register(b, activity, rotated.networkTicket(), "127.0.0.1:41005");
        assertThat(peers.list(a, activity, ga.networkTicket())).singleElement().satisfies(peer -> {
            assertThat(peer.publicKey()).isEqualTo(key(5));
            assertThat(peer.endpoint()).isEqualTo("127.0.0.1:41005");
        });
        assertThat(access.validate(gc.networkTicket()).grant().id()).isEqualTo(gc.grantId());
        var transferred = AuthenticatedActivities.join(context, b, room.id(), "new-device", null, "transfer");
        assertThat(peers.list(a, activity, ga.networkTicket())).isEmpty();
        assertThatThrownBy(() -> access.validate(rotated.networkTicket())).isInstanceOf(ApiException.class);
        var replacement = grants.network(b, activity, transferred.deviceId(), key(4));
        peers.register(b, activity, replacement.networkTicket(), "127.0.0.1:41004");
        assertThat(peers.list(a, activity, ga.networkTicket())).singleElement().extracting(NetworkPeerService.Peer::publicKey).isEqualTo(key(4));
        coordinator.leave(b, activity, transferred.deviceId(), transferred.leaseId());
        assertThat(peers.list(a, activity, ga.networkTicket())).isEmpty();
        assertThatThrownBy(() -> access.validate(replacement.networkTicket())).isInstanceOf(ApiException.class);
        var policies = context.getBean(NetworkPolicyService.class);
        policies.update("test", NetworkPolicyMode.RELAY_ONLY, policies.current().revision());
        assertThatThrownBy(() -> access.validate(ga.networkTicket())).isInstanceOf(ApiException.class);
        assertThat(http.send(httpRequest, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        var relayOnly = grants.network(a, activity, "device-a", key(1));
        assertThat(peers.register(a, activity, relayOnly.networkTicket(), null)).isEmpty();
        var relayJoin = AuthenticatedActivities.join(context, b, room.id(), "relay-device", null, "relay-b");
        var relayB = grants.network(b, activity, relayJoin.deviceId(), key(2));
        assertThat(peers.register(b, activity, relayB.networkTicket(), null)).singleElement().satisfies(peer -> {
            assertThat(peer.userId()).isEqualTo(a);
            assertThat(peer.endpoint()).isNull();
            assertThat(peer.publicKey()).isEqualTo(key(1));
        });
        assertThat(context.getBean(NetworkAccessController.class).validate(new NetworkAccessController.AccessBody(relayOnly.networkTicket())).peers())
                .singleElement().satisfies(peer -> assertThat(peer.endpoint()).isNull());
        assertThat(context.getBean(RelayGrantValidationService.class).validate(relayOnly.networkTicket()).allowRelay()).isTrue();
        if ("true".equals(System.getenv("ZHIJIE_TEST_RELAY_MEDIA"))) {
            var directory = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"), "relay-entry-").toAbsolutePath();
            var ready = directory.resolve("ready");
            var builder = new ProcessBuilder("cargo", "test", "--offline", "-p", "zhijie-relay", "--test", "control_plane", "--", "--ignored")
                    .redirectErrorStream(true).redirectOutput(directory.resolve("rust-relay.log").toFile());
            builder.environment().put("ZHIJIE_TEST_CONTROL_PLANE_URL", entry);
            builder.environment().put("ZHIJIE_TEST_RELAY_MANAGEMENT_KEY", relayManagementKey);
            builder.environment().put("ZHIJIE_TEST_NETWORK_TICKET_A", relayOnly.networkTicket());
            builder.environment().put("ZHIJIE_TEST_NETWORK_TICKET_B", relayB.networkTicket());
            builder.environment().put("ZHIJIE_TEST_NETWORK_IP_A", relayOnly.virtualIpv4());
            builder.environment().put("ZHIJIE_TEST_NETWORK_IP_B", relayB.virtualIpv4());
            builder.environment().put("ZHIJIE_TEST_RELAY_READY", ready.toString());
            var process = builder.start();
            try {
                for (int attempt = 0; attempt < 100 && process.isAlive() && !java.nio.file.Files.exists(ready); attempt++) Thread.sleep(100);
                assertThat(java.nio.file.Files.exists(ready)).as("QUIC transfer ready; inspect %s", directory).isTrue();
                coordinator.leave(b, activity, relayJoin.deviceId(), relayJoin.leaseId());
                assertThat(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).as("real control plane QUIC revocation; inspect %s", directory).isZero();
            } finally { if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); } }
        }
        var repository = context.getBean(ActivityRepository.class);
        var lease = repository.findLease(relayOnly.leaseId()).orElseThrow();
        repository.updateLease(new ConnectionLease(lease.id(), lease.activityId(), lease.userId(), lease.deviceId(),
                lease.activityGeneration(), lease.memberGeneration(), lease.policyRevision(), lease.voiceState(), lease.networkState(), Instant.now().minusSeconds(1), null));
        assertThatThrownBy(() -> access.validate(relayOnly.networkTicket())).isInstanceOf(ApiException.class);
    }

    @Test void fullRoomReturnsElevenCurrentPeersAndRemovesOnlyTheDepartingMember() throws Exception {
        var identities = context.getBean(IdentityRepository.class);
        var groups = context.getBean(GroupService.class);
        var rooms = context.getBean(RoomService.class);
        var coordinator = context.getBean(ActivityCoordinator.class);
        var grants = context.getBean(GrantService.class);
        var peers = context.getBean(NetworkPeerService.class);
        var users = new ArrayList<String>();
        for (int index = 0; index < 13; index++) {
            String user = UUID.randomUUID().toString();
            identities.insertUser(new UserAccount(user, user + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
            users.add(user);
        }
        String owner = users.getFirst();
        var group = groups.createGroup(owner, "十二端网络", null, "");
        try {
            for (String user : users.subList(1, users.size())) {
                groups.acceptInvitation(user, groups.invite(owner, group.id(), user).id());
            }
            var room = rooms.createRoom(owner, group.id(), "满房 Peer", "", "", null);
            String activity = rooms.startActivity(owner, room.id()).id();
            var tickets = new ArrayList<String>();
            var leases = new ArrayList<String>();
            var addresses = new HashSet<String>();
            for (int index = 0; index < 12; index++) {
                String user = users.get(index);
                leases.add(AuthenticatedActivities.join(context, user, room.id(), "device-" + index, null, "full-" + index).leaseId());
                var grant = grants.network(user, activity, "device-" + index, key(index + 1));
                tickets.add(grant.networkTicket());
                assertThat(addresses.add(grant.virtualIpv4())).isTrue();
                peers.register(user, activity, grant.networkTicket(),
                        grant.allowedPaths().contains("DIRECT") ? "127.0.0.1:" + (42000 + index) : null);
            }
            assertThatThrownBy(() -> AuthenticatedActivities.join(context, users.get(12), room.id(), "device-13", null, "full-13"))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CAPACITY_FULL");
            String entry = "http://127.0.0.1:" + ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var http = HttpClient.newHttpClient();
            var json = context.getBean(ObjectMapper.class);
            for (int index = 0; index < 12; index++) {
                var request = HttpRequest.newBuilder(URI.create(entry + "/api/v1/network-access/validate"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("ticket", tickets.get(index))))).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var body = json.readTree(response.body());
                assertThat(body.path("peers").size()).isEqualTo(11);
                assertThat(response.body()).doesNotContain("privateKey", tickets.get(index));
                var expected = new HashSet<>(users.subList(0, 12));
                expected.remove(users.get(index));
                var actual = new HashSet<String>();
                body.path("peers").forEach(peer -> actual.add(peer.path("userId").asText()));
                assertThat(actual).isEqualTo(expected);
            }
            coordinator.leave(users.get(11), activity, "device-11", leases.get(11));
            for (int index = 0; index < 11; index++) {
                assertThat(peers.list(users.get(index), activity, tickets.get(index)))
                        .hasSize(10).extracting(NetworkPeerService.Peer::userId).doesNotContain(users.get(11));
            }
            assertThatThrownBy(() -> context.getBean(NetworkAccessService.class).validate(tickets.get(11)))
                    .isInstanceOf(ApiException.class);
        } finally {
            groups.dissolveGroup(owner, group.id());
        }
    }

    @Test void realFullRoomDataPumpsUseIssuedTicketsAndStopOnlyTheDepartingMember() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("ZHIJIE_TEST_RELAY_MEDIA")));
        for (var mode : List.of(NetworkPolicyMode.DIRECT_ONLY, NetworkPolicyMode.RELAY_ONLY, NetworkPolicyMode.DIRECT_WITH_RELAY_FALLBACK)) {
            runRealFullRoom(mode);
        }
    }

    private void runRealFullRoom(NetworkPolicyMode mode) throws Exception {
        FullRoomNetworkProbe.run((ServletWebServerApplicationContext)context, relayManagementKey, mode, null, null, null, null);
    }

    @Test void revokedDirectPeerCannotKeepSendingEvenWhenReceiverLosesControlPlane() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("ZHIJIE_TEST_RELAY_MEDIA")));
        for (String mode : List.of("online", "offline")) {
            HostileNetworkProbe.run((ServletWebServerApplicationContext)context, mode);
        }
    }

    private static String key(int value) {
        byte[] bytes = new byte[32]; Arrays.fill(bytes, (byte)value);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
