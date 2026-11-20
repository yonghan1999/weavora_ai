package app.weavora.zhijie.controlplane.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.JoinResult;
import app.weavora.zhijie.controlplane.grant.NetworkGrantResponse;
import app.weavora.zhijie.controlplane.grant.VoiceGrantResponse;
import app.weavora.zhijie.controlplane.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class IssuedGrantRevocationIntegrationTest {
    private enum Action { LEAVE, KICK, LEAVE_GROUP, REMOVE_MEMBER, END_ACTIVITY, DELETE_ROOM, DISABLE, TAKEOVER, SWITCH_ROOM, DISSOLVE }
    private static EmbeddedPostgres postgres;
    private static ServletWebServerApplicationContext context;
    private static HttpClient http;
    private static ObjectMapper json;
    private static HttpServer unavailableVoice;
    private static String entry;
    private static final String managementKey = UUID.randomUUID().toString();

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.start();
        try {
            unavailableVoice = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            unavailableVoice.createContext("/", exchange -> {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });
            unavailableVoice.start();
            context = (ServletWebServerApplicationContext) new SpringApplication(ControlPlaneApplication.class).run(
                    "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=true",
                    "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                    "--spring.datasource.username=postgres", "--spring.datasource.password=",
                    "--zhijie.voice.enabled=true", "--zhijie.voice.url=wss://voice.example.test",
                    "--zhijie.voice.admin-url=http://127.0.0.1:" + unavailableVoice.getAddress().getPort(),
                    "--zhijie.voice.api-key=issued-grant-test", "--zhijie.voice.api-secret=" + UUID.randomUUID(),
                    "--zhijie.voice.signaling-key=" + managementKey, "--zhijie.relay.management-key=" + managementKey);
            json = context.getBean(ObjectMapper.class);
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            entry = "http://127.0.0.1:" + context.getWebServer().getPort();
        } catch (Exception | AssertionError failure) {
            if (unavailableVoice != null) unavailableVoice.stop(0);
            postgres.close();
            throw failure;
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (http != null) http.close();
        if (context != null) context.close();
        if (unavailableVoice != null) unavailableVoice.stop(0);
        if (postgres != null) postgres.close();
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void issuedTicketsAndSignalingRejectRevokedMembersWithoutClientCleanup(Action action) throws Exception {
        var scene = scene();
        assertGrants(scene.subject(), true);
        assertGrants(scene.observer(), true);
        assertPeers(scene.observer(), scene.subject().tokens().userId());
        var rotated = data("POST", "/api/v1/auth/refresh", null,
                Map.of("refreshToken", scene.subject().tokens().refreshToken()), AuthTokens.class);
        var subject = new Participant(rotated, scene.subject().joined(), scene.subject().network(), scene.subject().voice());
        assertGrants(subject, true);
        JoinResult replacement = switch (action) {
            case LEAVE -> {
                data("POST", "/api/v1/activities/" + scene.activity() + "/leave", rotated,
                        Map.of("leaseId", subject.joined().leaseId()), JsonNode.class);
                yield null;
            }
            case KICK -> {
                data("POST", "/api/v1/activities/" + scene.activity() + "/members/" + rotated.userId() + "/kick",
                        scene.observer().tokens(), Map.of("memberGeneration", subject.joined().memberGeneration()), JsonNode.class);
                yield null;
            }
            case LEAVE_GROUP -> {
                data("POST", "/api/v1/groups/" + scene.group() + "/leave", rotated, Map.of(), JsonNode.class);
                yield null;
            }
            case REMOVE_MEMBER -> {
                data("DELETE", "/api/v1/groups/" + scene.group() + "/members/" + rotated.userId(),
                        scene.observer().tokens(), Map.of(), JsonNode.class);
                yield null;
            }
            case END_ACTIVITY -> {
                data("POST", "/api/v1/activities/" + scene.activity() + "/end", scene.observer().tokens(), Map.of(), JsonNode.class);
                yield null;
            }
            case DELETE_ROOM -> {
                data("DELETE", "/api/v1/rooms/" + scene.room(), scene.observer().tokens(), Map.of(), JsonNode.class);
                yield null;
            }
            case DISABLE -> {
                context.getBean(JdbcTemplate.class).update("UPDATE users SET status='DISABLED' WHERE id=?", rotated.userId());
                yield null;
            }
            case TAKEOVER -> {
                var other = login(rotated.userId(), scene.password(), "other-device");
                yield join(other, scene.room());
            }
            case SWITCH_ROOM -> {
                String other = createRoom(scene.observer().tokens(), scene.group());
                yield join(rotated, other);
            }
            case DISSOLVE -> {
                data("POST", "/api/v1/groups/" + scene.group() + "/dissolve", scene.observer().tokens(), Map.of(), JsonNode.class);
                yield null;
            }
        };
        long confirmed = System.nanoTime();
        assertGrants(subject, false);
        boolean wholeRoom = action == Action.END_ACTIVITY || action == Action.DELETE_ROOM || action == Action.DISSOLVE;
        assertGrants(scene.observer(), !wholeRoom);
        if (!wholeRoom) {
            assertPeers(scene.observer(), null);
            var joined = scene.observer().joined();
            data("POST", "/api/v1/activities/" + scene.activity() + "/heartbeat", scene.observer().tokens(),
                    Map.of("deviceId", joined.deviceId(), "leaseId", joined.leaseId(),
                            "activityGeneration", joined.activityGeneration(), "memberGeneration", joined.memberGeneration()), JsonNode.class);
        }
        var sql = context.getBean(JdbcTemplate.class);
        long deadline = confirmed + TimeUnit.SECONDS.toNanos(5);
        while (sql.queryForObject("SELECT revoked_at IS NULL FROM connection_leases WHERE id=?", Boolean.class,
                subject.joined().leaseId()) && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(sql.queryForObject("SELECT revoked_at IS NOT NULL FROM connection_leases WHERE id=?", Boolean.class,
                subject.joined().leaseId())).isTrue();
        assertThat(sql.queryForObject("SELECT count(*) FROM activity_grants WHERE id IN (?,?) AND revoked_at IS NOT NULL",
                Integer.class, subject.network().grantId(), subject.voice().grantId())).isEqualTo(2);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - confirmed);
        assertThat(elapsed).as("Issued grant revocation after %s", action).isLessThanOrEqualTo(5000);
        if (replacement != null) {
            assertThat(sql.queryForObject("SELECT activity_id FROM account_activity WHERE user_id=?", String.class, rotated.userId()))
                    .isEqualTo(replacement.activityId());
            assertThat(sql.queryForObject("SELECT revoked_at IS NULL FROM connection_leases WHERE id=?", Boolean.class,
                    replacement.leaseId())).isTrue();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND type IN ('voice.revoked','network.revoked') AND delivered_at IS NULL",
                Integer.class, rotated.userId())).isGreaterThanOrEqualTo(2);
        Thread.sleep(1000);
        assertGrants(subject, false);
        System.out.println("ISSUED_GRANT_REVOKED " + action + " AUTHORITY_MS=" + elapsed);
    }

    @Test
    void authenticationOnlyLogoutLeavesIssuedActivityGrantsValidUntilActivityRevocation() throws Exception {
        var scene = scene();
        var subject = scene.subject();
        var other = login(subject.tokens().userId(), scene.password(), "other-device");
        assertGrants(subject, true);
        data("POST", "/api/v1/auth/logout", null, Map.of("accessToken", other.accessToken()), JsonNode.class);
        assertGrants(subject, true);
        data("POST", "/api/v1/auth/logout", null, Map.of("accessToken", subject.tokens().accessToken()), JsonNode.class);
        assertThat(request("GET", "/api/v1/groups", subject.tokens(), null).statusCode()).isEqualTo(401);
        assertThat(request("POST", "/api/v1/auth/refresh", null, Map.of("refreshToken", subject.tokens().refreshToken())).statusCode()).isEqualTo(401);
        assertGrants(subject, true);
        assertPeers(scene.observer(), subject.tokens().userId());
        assertThat(context.getBean(JdbcTemplate.class).queryForObject("SELECT revoked_at IS NULL FROM connection_leases WHERE id=?",
                Boolean.class, subject.joined().leaseId())).isTrue();
        data("POST", "/api/v1/activities/" + scene.activity() + "/members/" + subject.tokens().userId() + "/kick",
                scene.observer().tokens(), Map.of("memberGeneration", subject.joined().memberGeneration()), JsonNode.class);
        assertGrants(subject, false);
        assertGrants(scene.observer(), true);
        assertPeers(scene.observer(), null);
        System.out.println("AUTH_ONLY_LOGOUT_BOUNDARY authentication_denied=true issued_network_and_voice_still_valid=true activity_revocation_then_denied=true");
    }

    @Test
    void explicitLeaveThenLogoutRejectsIssuedGrantsAndPreservesAnotherLogin() throws Exception {
        var scene = scene();
        var subject = scene.subject();
        var other = login(subject.tokens().userId(), scene.password(), "other-device");
        assertGrants(subject, true);
        data("POST", "/api/v1/activities/" + scene.activity() + "/leave", subject.tokens(),
                Map.of("leaseId", subject.joined().leaseId()), JsonNode.class);
        data("POST", "/api/v1/auth/logout", null, Map.of("accessToken", subject.tokens().accessToken()), JsonNode.class);
        assertThat(request("GET", "/api/v1/groups", subject.tokens(), null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/v1/groups", other, null).statusCode()).isEqualTo(200);
        assertGrants(subject, false);
        assertGrants(scene.observer(), true);
        assertPeers(scene.observer(), null);
        System.out.println("EXPLICIT_LEAVE_AND_LOGOUT issued_network_voice_relay_denied=true other_login_preserved=true");
    }

    private Scene scene() throws Exception {
        String owner = UUID.randomUUID().toString(), member = UUID.randomUUID().toString();
        String password = UUID.randomUUID().toString();
        String hash = context.getBean(CredentialProtector.class).hashPassword(password);
        var repository = context.getBean(IdentityRepository.class);
        for (String user : List.of(owner, member)) {
            repository.insertUser(new UserAccount(user, user + "@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
        }
        var ownerTokens = login(owner, password, "owner-device");
        var memberTokens = login(member, password, "member-device");
        String group = data("POST", "/api/v1/groups", ownerTokens, Map.of("name", "已签发票据撤销"), JsonNode.class).path("id").asText();
        String invitation = data("POST", "/api/v1/groups/" + group + "/invitations", ownerTokens,
                Map.of("inviteeId", member), JsonNode.class).path("id").asText();
        data("POST", "/api/v1/invitations/" + invitation + "/accept", memberTokens, Map.of(), JsonNode.class);
        String room = createRoom(ownerTokens, group);
        var observer = participant(ownerTokens, join(ownerTokens, room), 1);
        var subject = participant(memberTokens, join(memberTokens, room), 2);
        return new Scene(group, room, subject.joined().activityId(), password, subject, observer);
    }

    private AuthTokens login(String user, String password, String device) throws Exception {
        return data("POST", "/api/v1/auth/login", null,
                Map.of("email", user + "@example.test", "password", password, "deviceId", device), AuthTokens.class);
    }

    private String createRoom(AuthTokens owner, String group) throws Exception {
        String room = data("POST", "/api/v1/groups/" + group + "/rooms", owner, Map.of("name", "票据验证房"), JsonNode.class).path("id").asText();
        data("POST", "/api/v1/rooms/" + room + "/activities", owner, Map.of(), JsonNode.class);
        return room;
    }

    private JoinResult join(AuthTokens tokens, String room) throws Exception {
        String device = context.getBean(IdentityRepository.class).findSessionById(tokens.sessionId()).orElseThrow().deviceId();
        return data("POST", "/api/v1/room-joins", tokens,
                Map.of("roomId", room, "deviceId", device, "operationId", UUID.randomUUID().toString()), JoinResult.class);
    }

    private Participant participant(AuthTokens tokens, JoinResult joined, int keyValue) throws Exception {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) keyValue);
        var network = data("POST", "/api/v1/activities/" + joined.activityId() + "/network-grants", tokens,
                Map.of("publicKey", Base64.getEncoder().encodeToString(bytes)), NetworkGrantResponse.class);
        var voice = data("POST", "/api/v1/activities/" + joined.activityId() + "/voice-grants", tokens, Map.of(), VoiceGrantResponse.class);
        data("POST", "/api/v1/activities/" + joined.activityId() + "/network-peers/register", tokens,
                Map.of("ticket", network.networkTicket(), "endpoint", "127.0.0.1:" + (42000 + keyValue)), JsonNode.class);
        return new Participant(tokens, joined, network, voice);
    }

    private void assertGrants(Participant participant, boolean valid) throws Exception {
        assertThat(Instant.now()).as("Old network ticket has not naturally expired").isBefore(participant.network().expiresAt());
        assertThat(Instant.now()).as("Old voice token has not naturally expired").isBefore(participant.voice().expiresAt());
        var network = request("POST", "/api/v1/network-access/validate", null, Map.of("ticket", participant.network().networkTicket()));
        assertThat(network.statusCode()).as("Previously issued network ticket, valid=%s", valid).isEqualTo(valid ? 200 : 403);
        var relay = HttpRequest.newBuilder(URI.create(entry + "/internal/relay/v1/validate")).timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json").header("X-Relay-Key", managementKey)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("ticket", participant.network().networkTicket(), "clientEndpoint", "127.0.0.1:42999")))).build();
        var relayResponse = http.send(relay, HttpResponse.BodyHandlers.ofString());
        assertThat(relayResponse.statusCode()).as("Previously issued Relay authorization, valid=%s", valid).isEqualTo(valid ? 200 : 403);
        var voice = HttpRequest.newBuilder(URI.create(entry + "/internal/voice/v1/authorize")).timeout(Duration.ofSeconds(2))
                .header("X-Voice-Key", managementKey).header("Authorization", "Bearer " + participant.voice().voiceToken()).GET().build();
        var voiceResponse = http.send(voice, HttpResponse.BodyHandlers.ofString());
        assertThat(voiceResponse.statusCode()).as("Previously issued voice signaling token, valid=%s", valid).isEqualTo(valid ? 204 : 403);
        assertThat(network.body()).doesNotContain(participant.network().networkTicket(), participant.voice().voiceToken());
        assertThat(voiceResponse.body()).doesNotContain(participant.network().networkTicket(), participant.voice().voiceToken());
        if (!valid) {
            assertThat(relayResponse.body()).doesNotContain(participant.network().networkTicket(), participant.voice().voiceToken());
            assertThat(json.readTree(network.body()).path("error").path("code").asText()).isIn("NETWORK_GRANT_INVALID", "CURRENT_GRANT_REQUIRED");
            assertThat(json.readTree(relayResponse.body()).path("error").path("code").asText()).isEqualTo("RELAY_GRANT_INVALID");
            assertThat(json.readTree(voiceResponse.body()).path("error").path("code").asText()).isEqualTo("VOICE_ACCESS_DENIED");
        }
    }

    private void assertPeers(Participant observer, String member) throws Exception {
        var response = request("POST", "/api/v1/network-access/validate", null, Map.of("ticket", observer.network().networkTicket()));
        assertThat(response.statusCode()).isEqualTo(200);
        var peers = json.readTree(response.body()).path("peers");
        assertThat(peers.size()).isEqualTo(member == null ? 0 : 1);
        if (member != null) assertThat(peers.get(0).path("userId").asText()).isEqualTo(member);
    }

    private <Result> Result data(String method, String path, AuthTokens tokens, Object body, Class<Result> type) throws Exception {
        var response = request(method, path, tokens, body);
        assertThat(response.statusCode()).as("HTTP %s %s", method, path).isEqualTo(200);
        return json.treeToValue(json.readTree(response.body()).path("data"), type);
    }

    private HttpResponse<String> request(String method, String path, AuthTokens tokens, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(entry + path)).timeout(Duration.ofSeconds(2)).header("Content-Type", "application/json");
        if (tokens != null) builder.header("Authorization", "Bearer " + tokens.accessToken());
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Participant(AuthTokens tokens, JoinResult joined, NetworkGrantResponse network, VoiceGrantResponse voice) { }
    private record Scene(String group, String room, String activity, String password, Participant subject, Participant observer) { }
}
