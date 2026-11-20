package app.weavora.zhijie.controlplane.grant;

import static org.assertj.core.api.Assertions.*;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import app.weavora.zhijie.controlplane.activity.JoinResult;
import app.weavora.zhijie.controlplane.activity.NetworkProbeRevocation;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.AuthTokens;
import app.weavora.zhijie.controlplane.room.RoomService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.*;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_NGINX", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_VOICE_MEDIA", matches = "true")
class VoiceSessionRevocationIntegrationTest {
    @Test void realAuthenticationRevocationStopsOnlyBoundMediaThroughScheduledOutbox() throws Exception {
        String apiKey = System.getenv("ZHIJIE_VOICE_API_KEY"), apiSecret = System.getenv("ZHIJIE_VOICE_API_SECRET");
        String signalingKey = UUID.randomUUID().toString();
        var directory = Files.createTempDirectory(Path.of("target"), "voice-session-").toAbsolutePath();
        var build = new ProcessBuilder("cargo", "test", "--locked", "--offline", "-p", "zhijie-voice", "--test", "livekit_media", "--no-run")
                .redirectErrorStream(true).redirectOutput(directory.resolve("build.log").toFile()).start();
        try {
            assertThat(build.waitFor(180, TimeUnit.SECONDS)).as("Voice probe build: %s", directory).isTrue();
            assertThat(build.exitValue()).as("Voice probe build: %s", directory).isZero();
        } finally { terminate(build); }
        int port;
        try (var reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = reservation.getLocalPort(); }
        String entry = "http://127.0.0.1:" + port;
        try (var postgres = EmbeddedPostgres.start();
             var context = (ServletWebServerApplicationContext)new SpringApplication(ControlPlaneApplication.class).run(
                     "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=true",
                     "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                     "--spring.datasource.username=postgres", "--spring.datasource.password=",
                     "--zhijie.voice.enabled=true", "--zhijie.voice.url=" + entry.replace("http://", "ws://"),
                     "--zhijie.voice.admin-url=http://127.0.0.1:7880", "--zhijie.voice.api-key=" + apiKey,
                     "--zhijie.voice.api-secret=" + apiSecret, "--zhijie.voice.signaling-key=" + signalingKey);
             var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            var builder = new ProcessBuilder("python", "-B", "scripts/local-preview/voice-proxy.py", "--nginx",
                    Path.of(System.getenv("ZHIJIE_TEST_NGINX")).toAbsolutePath().toString(), "--port", Integer.toString(port),
                    "--control-port", Integer.toString(context.getWebServer().getPort()));
            builder.environment().put("ZHIJIE_VOICE_SIGNALING_KEY", signalingKey);
            var proxy = builder.redirectOutput(directory.resolve("proxy.log").toFile()).redirectError(directory.resolve("proxy-error.log").toFile()).start();
            try {
                boolean ready = false;
                for (int attempt = 0; attempt < 100 && proxy.isAlive(); attempt++) {
                    try {
                        var response = http.send(HttpRequest.newBuilder(URI.create(entry)).timeout(Duration.ofMillis(300)).GET().build(),
                                HttpResponse.BodyHandlers.discarding());
                        ready = response.statusCode() == 404;
                        if (ready) break;
                    } catch (java.io.IOException unavailable) { Thread.sleep(100); }
                }
                assertThat(ready).as("Real auth proxy: %s", directory).isTrue();
                for (var action : List.of(NetworkProbeRevocation.LOGOUT, NetworkProbeRevocation.REFRESH_REPLAY, NetworkProbeRevocation.SESSION_EXPIRED)) {
                    run(context, http, entry, directory, action, List.of(apiKey, apiSecret, signalingKey));
                }
            } finally {
                if (proxy.isAlive()) {
                    proxy.getOutputStream().close();
                    try {
                        assertThat(proxy.waitFor(25, TimeUnit.SECONDS)).as("Auth proxy cleanup").isTrue();
                        assertThat(proxy.exitValue()).isZero();
                    } finally { terminate(proxy); }
                }
            }
        }
    }

    private void run(ServletWebServerApplicationContext context, HttpClient http, String entry, Path parent,
                     NetworkProbeRevocation action, List<String> secrets) throws Exception {
        var directory = Files.createDirectory(parent.resolve(action.name()));
        var tokens = new ArrayList<AuthTokens>();
        for (int index = 0; index < 3; index++) tokens.add(NetworkProbeRevocation.login(context, "voice-" + index));
        var groups = context.getBean(GroupService.class);
        String owner = tokens.getFirst().userId();
        var group = groups.createGroup(owner, "真实认证语音撤销", null, "");
        Process process = null;
        try {
            for (var token : tokens.subList(1, 3)) groups.acceptInvitation(token.userId(), groups.invite(owner, group.id(), token.userId()).id());
            var rooms = context.getBean(RoomService.class);
            var room = rooms.createRoom(owner, group.id(), "持续音频隔离", "", "", null);
            String activity = rooms.startActivity(owner, room.id()).id();
            var joins = new ArrayList<JoinResult>();
            var grants = new ArrayList<VoiceGrantResponse>();
            for (var token : tokens) {
                var session = NetworkProbeRevocation.session(context, token);
                joins.add(context.getBean(ActivityCoordinator.class).join(session, room.id(), null, UUID.randomUUID().toString()));
                grants.add(context.getBean(GrantService.class).voice(session, activity));
            }
            var rotated = NetworkProbeRevocation.refresh(context, tokens.get(1));
            for (var grant : grants) assertThat(validate(http, entry, grant.voiceToken())).isEqualTo(200);
            var builder = new ProcessBuilder("cargo", "test", "--locked", "--offline", "-p", "zhijie-voice", "--test", "livekit_media",
                    "session_revocation::authentication_revocation_stops_only_target_media_without_client_stop", "--", "--ignored", "--exact", "--nocapture");
            builder.environment().put("ZHIJIE_TEST_VOICE_URL", entry.replace("http://", "ws://"));
            builder.environment().put("ZHIJIE_TEST_VOICE_DIRECTORY", directory.toString());
            for (int index = 0; index < 3; index++) builder.environment().put("ZHIJIE_TEST_VOICE_TOKEN_" + index, grants.get(index).voiceToken());
            for (String key : new ArrayList<>(builder.environment().keySet())) {
                if (key.startsWith("LIVEKIT_") || key.startsWith("ZHIJIE_VOICE_") || key.equals("ZHIJIE_TEST_VOICE_ADMIN_TOKEN")) builder.environment().remove(key);
            }
            var output = directory.resolve("media.log");
            process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
            long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (process.isAlive() && !Files.exists(directory.resolve("ready")) && System.nanoTime() < readyDeadline) Thread.sleep(10);
            assertThat(Files.exists(directory.resolve("ready"))).as("Actual six-direction PCM before %s: %s", action, output).isTrue();
            for (var grant : grants) assertThat(grant.expiresAt()).isAfter(Instant.now().plusSeconds(10));
            action.revoke(context, tokens.get(1), rotated, activity, joins.get(1).leaseId());
            long confirmed = System.nanoTime();
            Files.writeString(directory.resolve("revoked"), "CONFIRMED");
            while (process.isAlive() && !Files.exists(directory.resolve("isolated")) && System.nanoTime() - confirmed < TimeUnit.SECONDS.toNanos(5)) Thread.sleep(10);
            assertThat(Files.exists(directory.resolve("isolated"))).as("Target audio isolated within five seconds: %s", output).isTrue();
            long observedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - confirmed);
            assertThat(observedMillis).isLessThan(5000);
            NetworkProbeRevocation.assertPersisted(context, tokens.get(1).userId(), activity, joins.get(1).leaseId(), confirmed);
            var sql = context.getBean(JdbcTemplate.class);
            assertThat(sql.queryForObject("SELECT count(*) FROM outbox_events WHERE activity_id=? AND aggregate_id=? AND type='voice.revoked' AND delivered_at IS NOT NULL",
                    Integer.class, activity, tokens.get(1).userId())).isEqualTo(1);
            for (int index = 0; index < 3; index++) {
                assertThat(grants.get(index).expiresAt()).isAfter(Instant.now());
                assertThat(validate(http, entry, grants.get(index).voiceToken())).isEqualTo(index == 1 ? 403 : 200);
            }
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("Media cleanup: %s", output).isTrue();
            assertThat(process.exitValue()).as("Real media revocation: %s", output).isZero();
            String logs = Files.readString(output);
            assertThat(logs).contains("REAL_AUTH_VOICE_ISOLATED directions=6", "old_publisher_stopped=false", "REAL_AUTH_VOICE_STALE_RECONNECT_REJECTED");
            for (var grant : grants) assertThat(logs).doesNotContain(grant.voiceToken());
            for (var token : tokens) assertThat(logs).doesNotContain(token.accessToken(), token.refreshToken());
            for (String secret : secrets) assertThat(logs).doesNotContain(secret);
            assertThat(logs).doesNotContain(rotated.accessToken(), rotated.refreshToken());
            System.out.println("AUTH_SESSION_MEDIA_REVOKED action=" + action + " observed_ms=" + observedMillis + " directory=" + directory);
        } finally {
            terminate(process);
            groups.dissolveGroup(owner, group.id());
        }
    }

    private static int validate(HttpClient http, String entry, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(entry + "/rtc/validate")).timeout(Duration.ofSeconds(2))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static void terminate(Process process) {
        if (process != null && process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
    }
}
