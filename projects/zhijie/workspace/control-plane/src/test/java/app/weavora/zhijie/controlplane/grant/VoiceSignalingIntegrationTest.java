package app.weavora.zhijie.controlplane.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.activity.AuthenticatedActivities;
import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.room.RoomService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/** 真实 PostgreSQL、控制面、nginx auth_request 和本机 SFU，显式运行。 */
@EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_NGINX", matches = ".+")
class VoiceSignalingIntegrationTest {
    @Test
    void realProxyRejectsRevokedAndOldGenerationTokensBeforeTheSfu() throws Exception {
        String apiKey = System.getenv("ZHIJIE_VOICE_API_KEY");
        String apiSecret = System.getenv("ZHIJIE_VOICE_API_SECRET");
        String signalingKey = UUID.randomUUID().toString();
        String relayKey = UUID.randomUUID().toString();
        Process nginx = null;
        Path directory = Files.createTempDirectory(Path.of("target"), "voice-entry-").toAbsolutePath();
        if ("true".equals(System.getenv("ZHIJIE_TEST_VOICE_MEDIA"))) {
            if (combined()) {
                var networkBuild = new ProcessBuilder("cargo", "test", "--offline", "-p", "zhijie-helper", "--lib", "--no-run")
                        .redirectErrorStream(true).redirectOutput(directory.resolve("network-build.log").toFile()).start();
                try {
                    assertThat(networkBuild.waitFor(180, TimeUnit.SECONDS)).isTrue();
                    assertThat(networkBuild.exitValue()).isZero();
                } finally {
                    if (networkBuild.isAlive()) { networkBuild.descendants().forEach(ProcessHandle::destroyForcibly); networkBuild.destroyForcibly(); }
                }
            }
            // 原生 SDK 首次编译可能超过短令牌 TTL，必须先构建再创建成员和签票。
            var build = new ProcessBuilder("cargo", "test", "--offline", "-p", "zhijie-voice", "--test",
                    "livekit_media", "--no-run").redirectErrorStream(true)
                    .redirectOutput(directory.resolve("rust-build.log").toFile()).start();
            try {
                assertThat(build.waitFor(180, TimeUnit.SECONDS)).as("Rust prebuild deadline").isTrue();
                assertThat(build.exitValue()).as("Rust prebuild").isZero();
            } finally {
                if (build.isAlive()) {
                    build.descendants().forEach(ProcessHandle::destroyForcibly);
                    build.destroyForcibly();
                }
            }
        }
        int port;
        try (ServerSocket reserved = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { port = reserved.getLocalPort(); }
        String signalingUrl = "ws://127.0.0.1:" + port;
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start();
             var context = (ServletWebServerApplicationContext) new SpringApplication(ControlPlaneApplication.class).run(
                     "--server.address=127.0.0.1", "--server.port=0",
                     "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                     "--spring.datasource.username=postgres", "--spring.datasource.password=",
                     "--zhijie.scheduling.enabled=false", "--zhijie.voice.enabled=true", "--zhijie.voice.url=" + signalingUrl,
                     "--zhijie.voice.api-key=" + apiKey, "--zhijie.voice.api-secret=" + apiSecret,
                     "--zhijie.voice.signaling-key=" + signalingKey, "--zhijie.relay.management-key=" + relayKey)) {
            String executable = Path.of(System.getenv("ZHIJIE_TEST_NGINX")).toAbsolutePath().toString();
            var proxy = new ProcessBuilder("python", "-B", "scripts/local-preview/voice-proxy.py", "--nginx", executable,
                    "--port", String.valueOf(port), "--control-port", String.valueOf(context.getWebServer().getPort()));
            proxy.environment().put("ZHIJIE_VOICE_SIGNALING_KEY", signalingKey);
            nginx = proxy.redirectOutput(directory.resolve("out.log").toFile()).redirectError(directory.resolve("err.log").toFile()).start();
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String entry = "http://127.0.0.1:" + port;
            boolean ready = false;
            for (int attempt = 0; attempt < 40; attempt++) {
                if (!nginx.isAlive()) throw new AssertionError("nginx startup failed; inspect " + directory.resolve("err.log"));
                try { http.send(HttpRequest.newBuilder(URI.create(entry)).GET().build(), HttpResponse.BodyHandlers.discarding()); ready = true; break; }
                catch (java.io.IOException exception) { Thread.sleep(100); }
            }
            assertThat(ready).isTrue();
            String userId = UUID.randomUUID().toString();
            context.getBean(IdentityRepository.class).insertUser(new UserAccount(userId, userId + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
            var group = context.getBean(GroupService.class).createGroup(userId, "受控信令", null, "");
            var rooms = context.getBean(RoomService.class);
            var room = rooms.createRoom(userId, group.id(), "受控信令房间", "", "", null);
            rooms.startActivity(userId, room.id());
            var coordinator = context.getBean(ActivityCoordinator.class);
            var joined = AuthenticatedActivities.join(context, userId, room.id(), "device", null, "join");
            var grants = context.getBean(GrantService.class);
            var issued = grants.voice(userId, joined.activityId(), joined.deviceId());
            var retry = grants.voice(userId, joined.activityId(), joined.deviceId());
            assertThat(retry.grantId()).isNotEqualTo(issued.grantId());
            assertThat(retry.leaseId()).isEqualTo(issued.leaseId());
            assertThat(issued.sfuUrl()).isEqualTo(signalingUrl);
            String token = issued.voiceToken();
            assertThat(status(http, entry, token, false)).isEqualTo(200);
            assertThat(status(http, entry, retry.voiceToken(), true)).isEqualTo(200);
            var websocket = http.newWebSocketBuilder().header("Authorization", "Bearer " + token)
                    .buildAsync(URI.create(entry.replace("http://", "ws://") + "/rtc?protocol=15&auto_subscribe=1"), new WebSocket.Listener() {
                        @Override public CompletionStage<?> onBinary(WebSocket socket, java.nio.ByteBuffer data, boolean last) { socket.request(1); return null; }
                    }).get(5, TimeUnit.SECONDS);
            websocket.abort();
            if ("true".equals(System.getenv("ZHIJIE_TEST_VOICE_MEDIA"))) {
                String secondUser = UUID.randomUUID().toString();
                context.getBean(IdentityRepository.class).insertUser(new UserAccount(secondUser,
                        secondUser + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
                var groups = context.getBean(GroupService.class);
                groups.acceptInvitation(secondUser, groups.invite(userId, group.id(), secondUser).id());
                var secondJoin = AuthenticatedActivities.join(context, secondUser, room.id(), "second-device", null, "second-join");
                String secondToken = grants.voice(secondUser, secondJoin.activityId(), secondJoin.deviceId()).voiceToken();
                runMedia(directory, issued.sfuUrl(), token, secondToken,
                        adminToken(apiKey, apiSecret, joined.activityId()));
            }
            coordinator.leave(userId, joined.activityId(), joined.deviceId(), joined.leaseId());
            assertThat(status(http, entry, token, false)).isEqualTo(403);
            assertThat(status(http, entry, retry.voiceToken(), true)).isEqualTo(403);
            assertThatThrownBy(() -> http.newWebSocketBuilder().header("Authorization", "Bearer " + token)
                    .buildAsync(URI.create(entry.replace("http://", "ws://") + "/rtc?protocol=15"), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
            var rejoined = AuthenticatedActivities.join(context, userId, room.id(), "device", null, "rejoin");
            String fresh = grants.voice(userId, rejoined.activityId(), rejoined.deviceId()).voiceToken();
            assertThat(status(http, entry, token, false)).isEqualTo(403);
            assertThat(status(http, entry, fresh, false)).isEqualTo(200);
            assertThat(http.send(HttpRequest.newBuilder(URI.create(entry + "/twirp/livekit.RoomService/RemoveParticipant")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
            if ("true".equals(System.getenv("ZHIJIE_TEST_VOICE_MEDIA"))) {
                String secondUser = context.getBean(app.weavora.zhijie.controlplane.activity.ActivityRepository.class)
                        .listMembers(joined.activityId()).stream().filter(member -> !member.userId().equals(userId)).findFirst().orElseThrow().userId();
                String secondToken = grants.voice(secondUser, joined.activityId(), "second-device").voiceToken();
                var dispatcher = context.getBean(app.weavora.zhijie.controlplane.revocation.RevocationDispatcher.class);
                dispatcher.dispatchPending();
                runMedia(directory, entry.replace("http://", "ws://"), fresh, secondToken,
                        adminToken(apiKey, apiSecret, joined.activityId()), "dissolution", () -> {
                            context.getBean(GroupService.class).dissolveGroup(userId, group.id());
                            dispatcher.dispatchPending();
                        });
                assertThat(status(http, entry, fresh, false)).isEqualTo(403);
                assertThat(status(http, entry, secondToken, true)).isEqualTo(403);
                runFullRoom(context, directory, entry, http, userId, relayKey);
                var groups = context.getBean(GroupService.class);
                var disabledGroup = groups.createGroup(userId, "账号失效媒体", null, "");
                groups.acceptInvitation(secondUser, groups.invite(userId, disabledGroup.id(), secondUser).id());
                var disabledRoom = rooms.createRoom(userId, disabledGroup.id(), "账号失效媒体房", "", "", null);
                rooms.startActivity(userId, disabledRoom.id());
                var disabledJoin = AuthenticatedActivities.join(context, userId, disabledRoom.id(), "disabled-a", null, "disabled-a");
                AuthenticatedActivities.join(context, secondUser, disabledRoom.id(), "disabled-b", null, "disabled-b");
                String disabledFirst = grants.voice(userId, disabledJoin.activityId(), disabledJoin.deviceId()).voiceToken();
                String disabledSecond = grants.voice(secondUser, disabledJoin.activityId(), "disabled-b").voiceToken();
                runMedia(directory, entry.replace("http://", "ws://"), disabledFirst, disabledSecond,
                        adminToken(apiKey, apiSecret, disabledJoin.activityId()), "disabled-account", () -> {
                            context.getBean(org.springframework.jdbc.core.JdbcTemplate.class)
                                    .update("UPDATE users SET status = 'DISABLED' WHERE id IN (?, ?)", userId, secondUser);
                            context.getBean(app.weavora.zhijie.controlplane.revocation.DisabledAccountScheduler.class).reconcile();
                            dispatcher.dispatchPending();
                        });
                assertThat(status(http, entry, disabledFirst, false)).isEqualTo(403);
                assertThat(status(http, entry, disabledSecond, true)).isEqualTo(403);
            }
        } finally {
            if (nginx != null && nginx.isAlive()) {
                nginx.getOutputStream().close();
                assertThat(nginx.waitFor(25, TimeUnit.SECONDS)).as("preview proxy stopped").isTrue();
                assertThat(nginx.exitValue()).isZero();
            }
        }
    }

    private int status(HttpClient http, String entry, String token, boolean query) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(entry + "/rtc/validate" + (query ? "?access_token=" + token : ""))).timeout(Duration.ofSeconds(3));
        if (!query) request.header("Authorization", "Bearer " + token);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void runMedia(Path directory, String url, String first, String second, String admin) throws Exception {
        runMedia(directory, url, first, second, admin, "direct", null);
    }

    private void runMedia(Path directory, String url, String first, String second, String admin, String scenario, Runnable revoke) throws Exception {
        var builder = new ProcessBuilder("cargo", "test", "--offline", "-p", "zhijie-voice", "--test",
                "livekit_media", "bidirectional_audio_and_server_removal", "--", "--ignored", "--exact", "--nocapture");
        builder.environment().put("ZHIJIE_TEST_VOICE_URL", url);
        builder.environment().put("ZHIJIE_TEST_VOICE_ADMIN_URL", "http://127.0.0.1:7880");
        builder.environment().put("ZHIJIE_TEST_VOICE_TOKEN_A", first);
        builder.environment().put("ZHIJIE_TEST_VOICE_TOKEN_B", second);
        builder.environment().put("ZHIJIE_TEST_VOICE_ADMIN_TOKEN", admin);
        Path ready = directory.resolve("revocation-ready");
        Files.deleteIfExists(ready);
        if (revoke != null) builder.environment().put("ZHIJIE_TEST_VOICE_REVOCATION_READY", ready.toString());
        Path output = directory.resolve(revoke == null ? "rust-media.log" : "rust-" + scenario + "-media.log");
        var process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            if (revoke != null) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                while (!Files.exists(ready) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
                assertThat(Files.exists(ready)).as("Both clients received real PCM; inspect %s", output).isTrue();
                revoke.run();
            }
            assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("Rust media test deadline").isTrue();
            assertThat(process.exitValue()).as("Rust media through authenticated proxy; inspect %s", output).isZero();
            if (revoke == null) {
                assertThat(Files.readString(output).lines().filter(line -> line.startsWith("MEDIA_CONTROLS_OK ")).count())
                        .as("Three actual PCM mute/receive/recovery rounds").isEqualTo(3);
                assertThat(Files.readString(output).lines().filter(line -> line.startsWith("MEMBER_MEDIA_STATE_OK ")).count())
                        .as("Three actual SDK speaking/mute/recovery rounds").isEqualTo(3);
            }
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private void runFullRoom(ServletWebServerApplicationContext context, Path directory, String entry,
                             HttpClient http, String owner, String relayKey) throws Exception {
        var groups = context.getBean(GroupService.class);
        var rooms = context.getBean(RoomService.class);
        var coordinator = context.getBean(ActivityCoordinator.class);
        var grants = context.getBean(GrantService.class);
        var identities = context.getBean(IdentityRepository.class);
        var group = groups.createGroup(owner, "十二端受控音频", null, "");
        var room = rooms.createRoom(owner, group.id(), "满房音频", "", "", null);
        rooms.startActivity(owner, room.id());
        var users = new java.util.ArrayList<String>();
        var joins = new java.util.ArrayList<app.weavora.zhijie.controlplane.activity.JoinResult>();
        users.add(owner);
        for (int index = 1; index <= 12; index++) {
            String user = UUID.randomUUID().toString();
            identities.insertUser(new UserAccount(user, user + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
            groups.acceptInvitation(user, groups.invite(owner, group.id(), user).id());
            users.add(user);
        }
        for (int index = 0; index < 12; index++) {
            joins.add(AuthenticatedActivities.join(context, users.get(index), room.id(), "full-room-" + index, null, "join-" + index));
        }
        assertThatThrownBy(() -> AuthenticatedActivities.join(context, users.get(12), room.id(), "overflow", null, "overflow"))
                .isInstanceOf(app.weavora.zhijie.controlplane.common.ApiException.class)
                .extracting("code").isEqualTo("CAPACITY_FULL");
        var builder = new ProcessBuilder("cargo", "test", "--offline", "-p", "zhijie-voice", "--test",
                "livekit_media", "full_room_all_to_all_audio", "--", "--ignored", "--exact", "--nocapture");
        builder.environment().put("ZHIJIE_TEST_VOICE_URL", entry.replace("http://", "ws://"));
        Path voiceReady = directory.resolve("full-room-ready");
        if (combined()) {
            builder.environment().put("ZHIJIE_TEST_COMBINED_CHANNELS", "true");
            builder.environment().put("ZHIJIE_TEST_FULL_ROOM_READY", voiceReady.toString());
        }
        var originalTokens = new java.util.ArrayList<String>();
        for (int index = 0; index < 12; index++) {
            String token = grants.voice(users.get(index), joins.get(index).activityId(), joins.get(index).deviceId()).voiceToken();
            originalTokens.add(token);
            builder.environment().put("ZHIJIE_TEST_VOICE_TOKEN_" + index, token);
        }
        Path output = directory.resolve("rust-full-room-media.log");
        var process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        var networkWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
        var networkStopped = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.Future<?> network = null;
        if (combined()) {
            network = networkWorker.submit(() -> {
                try {
                    app.weavora.zhijie.controlplane.network.FullRoomNetworkProbe.run(context, relayKey,
                            app.weavora.zhijie.controlplane.network.NetworkPolicyMode.RELAY_ONLY, users, joins,
                            joins.getFirst().activityId(), voiceReady);
                    assertThat(process.isAlive()).as("Voice stays active after network stops").isTrue();
                    networkStopped.set(System.nanoTime());
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
        }
        int renewals = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(combined() ? 145 : 115);
        try {
            while (!process.waitFor(15, TimeUnit.SECONDS) && System.nanoTime() < deadline) {
                for (int index = 0; index < 12; index++) {
                    var joined = joins.get(index);
                    coordinator.heartbeat(users.get(index), joined.activityId(), joined.deviceId(), joined.leaseId(),
                            joined.activityGeneration(), joined.memberGeneration());
                    String refreshed = grants.voice(users.get(index), joined.activityId(), joined.deviceId()).voiceToken();
                    assertThat(status(http, entry, refreshed, false)).isEqualTo(200);
                }
                renewals++;
            }
            assertThat(process.isAlive()).as("Full-room media deadline; inspect %s", output).isFalse();
            assertThat(process.exitValue()).as("132 decoded PCM directions; inspect %s", output).isZero();
            assertThat(renewals).as("Keep ordinary 60-second leases alive with 15-second heartbeats").isGreaterThanOrEqualTo(4);
            for (String expired : originalTokens) assertThat(status(http, entry, expired, false)).isEqualTo(403);
            String evidence = Files.readString(output);
            assertThat(evidence.lines().filter(line -> line.startsWith("PCM_DIRECTION ")).count()).isEqualTo(132);
            assertThat(evidence).contains(combined()
                    ? "FULL_ROOM_OK participants=12 directions=132 observation_seconds=105 windows=7"
                    : "FULL_ROOM_OK participants=12 directions=132 observation_seconds=75 windows=5");
            if (network != null) {
                network.get(15, TimeUnit.SECONDS);
                assertThat(System.nanoTime() - networkStopped.get()).as("PCM continues at least ten seconds after network revocation")
                        .isGreaterThan(TimeUnit.SECONDS.toNanos(10));
                assertThat(coordinator.listMembers(owner, joins.getFirst().activityId())).hasSize(12);
                System.out.println("COMBINED_CHANNELS_OK members=12 same_activity_lease=true voice_directions=132 network_policy_revoked_voice_continued=true");
            }
            System.out.println("FULL_ROOM_CONTROL_OK participants=12 heartbeat_batches=" + renewals + " overflow=CAPACITY_FULL expired_tokens=403");
        } finally {
            if (network != null && !network.isDone()) network.cancel(true);
            networkWorker.shutdownNow();
            assertThat(networkWorker.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            groups.dissolveGroup(owner, group.id());
            context.getBean(app.weavora.zhijie.controlplane.revocation.RevocationDispatcher.class).dispatchPending();
        }
    }

    private static boolean combined() {
        return "true".equals(System.getenv("ZHIJIE_TEST_COMBINED_CHANNELS"));
    }

    private String adminToken(String apiKey, String apiSecret, String activityId) throws Exception {
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(json.writeValueAsBytes(java.util.Map.of("iss", apiKey,
                "sub", "test-admin", "exp", Instant.now().plusSeconds(60).getEpochSecond(),
                "video", java.util.Map.of("roomAdmin", true, "room", activityId))));
        String input = header + "." + payload;
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(apiSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + encoder.encodeToString(mac.doFinal(input.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }
}
