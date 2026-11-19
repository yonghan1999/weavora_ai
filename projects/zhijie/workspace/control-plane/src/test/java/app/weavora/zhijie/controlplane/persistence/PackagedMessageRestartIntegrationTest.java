package app.weavora.zhijie.controlplane.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.identity.CredentialProtector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class PackagedMessageRestartIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_MESSAGE_RESTART_JAR", matches = ".+")
    void moderatedAndAuthorDeletionsSurviveNewServerProcessWithoutLeakingBodies() throws Exception {
        var directory = Files.createTempDirectory(Path.of("target"), "message-restart-");
        var jar = Path.of(System.getenv("ZHIJIE_TEST_MESSAGE_RESTART_JAR")).toRealPath();
        String password = UUID.randomUUID().toString();
        String group, retained, moderated, selfDeleted, owner, admin, author, adminId, authorId, auditId;
        String ownerId, room, invitee, inviteeId, pendingInvite;
        long firstPid;
        try (var postgres = EmbeddedPostgres.start()) {
            String database = postgres.getJdbcUrl("postgres", "postgres");
            try (var server = Server.start(jar, database, directory.resolve("before.log"))) {
                firstPid = server.process.pid();
                String hash = new CredentialProtector().hashPassword(password);
                try (var connection = postgres.getPostgresDatabase().getConnection(); var insert = connection.prepareStatement(
                        "INSERT INTO users(id,email_normalized,status,password_hash,created_at) VALUES (?,?,'ACTIVE',?,now())")) {
                    for (String name : new String[]{"owner", "admin", "author", "invitee"}) {
                        insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, name + "@example.test");
                        insert.setString(3, hash); assertThat(insert.executeUpdate()).isOne();
                    }
                }
                var ownerLogin = server.login("owner", password);
                owner = ownerLogin.required("accessToken").asText(); ownerId = ownerLogin.required("userId").asText();
                var adminLogin = server.login("admin", password);
                admin = adminLogin.required("accessToken").asText(); adminId = adminLogin.required("userId").asText();
                var authorLogin = server.login("author", password);
                author = authorLogin.required("accessToken").asText(); authorId = authorLogin.required("userId").asText();
                var inviteeLogin = server.login("invitee", password);
                invitee = inviteeLogin.required("accessToken").asText(); inviteeId = inviteeLogin.required("userId").asText();
                var friendRequest = server.data("POST", "/friend-requests", owner, Map.of("addresseeId", authorId));
                server.data("POST", "/friend-requests/" + friendRequest.required("id").asText() + "/accept", author, null);
                group = server.data("POST", "/groups", owner, Map.of("name", "消息持久化验收")).required("id").asText();
                for (var member : Map.of(adminId, admin, authorId, author).entrySet()) {
                    var invitation = server.data("POST", "/groups/" + group + "/invitations", owner, Map.of("inviteeId", member.getKey()));
                    server.data("POST", "/invitations/" + invitation.required("id").asText() + "/accept", member.getValue(), null);
                }
                server.role(group, owner, adminId, "ADMIN");
                pendingInvite = server.data("POST", "/groups/" + group + "/invitations", owner, Map.of("inviteeId", inviteeId)).required("id").asText();
                room = server.data("POST", "/groups/" + group + "/rooms", admin, Map.of("name", "保留的房间", "gameName", "协作游戏",
                        "description", "重启后仍是暂停状态", "password", UUID.randomUUID().toString())).required("id").asText();
                server.data("POST", "/rooms/" + room + "/pause", admin, null);
                String messages = "/groups/" + group + "/messages";
                retained = server.data("POST", messages, owner, Map.of("body", "retained-message")).required("id").asText();
                assertThat(server.call("DELETE", "/messages/" + retained, author, null).statusCode()).isEqualTo(403);
                moderated = server.data("POST", messages, author, Map.of("body", "removed-by-moderator")).required("id").asText();
                selfDeleted = server.data("POST", messages, author, Map.of("body", "removed-by-author")).required("id").asText();
                server.data("DELETE", "/messages/" + moderated, admin, null);
                server.data("DELETE", "/messages/" + selfDeleted, author, null);
                auditId = server.moderationAudit(group, admin, adminId, moderated).required("id").asText();
            }
            try (var restarted = Server.start(jar, database, directory.resolve("after.log"))) {
                assertThat(restarted.process.pid()).isNotEqualTo(firstPid);
                assertFriends(restarted, owner, authorId, author, ownerId);
                var savedGroup = restarted.data("GET", "/groups/" + group, author, null);
                assertThat(savedGroup.required("name").asText()).isEqualTo("消息持久化验收");
                assertThat(savedGroup.required("ownerUserId").asText()).isEqualTo(ownerId);
                var roles = restarted.data("GET", "/groups/" + group + "/members", author, null).valueStream()
                        .collect(java.util.stream.Collectors.toMap(row -> row.required("userId").asText(), row -> row.required("role").asText()));
                assertThat(roles).isEqualTo(Map.of(ownerId, "OWNER", adminId, "ADMIN", authorId, "MEMBER"));
                var savedRoom = restarted.data("GET", "/rooms/" + room, author, null);
                assertThat(savedRoom.required("name").asText()).isEqualTo("保留的房间");
                assertThat(savedRoom.required("description").asText()).isEqualTo("重启后仍是暂停状态");
                assertThat(savedRoom.required("gameName").asText()).isEqualTo("协作游戏");
                assertThat(savedRoom.required("openState").asText()).isEqualTo("PAUSED");
                assertThat(savedRoom.required("passwordProtected").booleanValue()).isTrue();
                assertThat(restarted.data("GET", "/invitations", invitee, null).findValuesAsText("id")).containsExactly(pendingInvite);
                String messages = "/groups/" + group + "/messages";
                for (String token : new String[]{author, admin, owner}) {
                    var history = restarted.data("GET", messages, token, null);
                    assertThat(history.toString()).doesNotContain("removed-by-moderator", "removed-by-author");
                    assertThat(history.size()).isEqualTo(3);
                    assertThat(history.findValuesAsText("id")).containsExactlyInAnyOrder(retained, moderated, selfDeleted);
                    for (var message : history) {
                        String id = message.required("id").asText();
                        if (id.equals(retained)) {
                            assertThat(message.required("deleted").asBoolean()).isFalse();
                            assertThat(message.required("body").asText()).isEqualTo("retained-message");
                        } else {
                            assertThat(message.required("deleted").asBoolean()).isTrue();
                            assertThat(message.required("body").isNull()).isTrue();
                            assertThat(message.required("deletionHint").asText()).isNotBlank();
                            assertThat(message.required("deleteKind").asText()).isEqualTo(id.equals(moderated) ? "MODERATOR" : "AUTHOR");
                            assertThat(message.required("deletedBy").asText()).isEqualTo(id.equals(moderated) ? adminId : authorId);
                        }
                    }
                }
                restarted.data("DELETE", "/messages/" + moderated, admin, null);
                assertThat(restarted.moderationAudit(group, admin, adminId, moderated).required("id").asText()).isEqualTo(auditId);
                restarted.role(group, owner, adminId, "MEMBER");
                assertThat(restarted.call("DELETE", "/messages/" + retained, admin, null).statusCode()).isEqualTo(403);
                assertThat(restarted.call("GET", "/groups/" + group + "/audit-logs", admin, null).statusCode()).isEqualTo(403);
                assertThat(restarted.moderationAudit(group, owner, adminId, moderated).required("id").asText()).isEqualTo(auditId);
                assertThat(restarted.data("POST", "/groups/" + group + "/dissolve", owner, null).required("dissolved").booleanValue()).isTrue();
                assertDissolved(restarted, group, room, owner, author, invitee, pendingInvite);
            }
            try (var afterDissolution = Server.start(jar, database, directory.resolve("dissolved.log"))) {
                assertDissolved(afterDissolution, group, room, owner, author, invitee, pendingInvite);
                assertFriends(afterDissolution, owner, authorId, author, ownerId);
                assertThat(afterDissolution.login("invitee", password).required("userId").asText()).isEqualTo(inviteeId);
            }
        }
    }

    private static void assertFriends(Server server, String owner, String authorId, String author, String ownerId) throws Exception {
        assertThat(server.data("GET", "/friends", owner, null).findValuesAsText("userId")).containsExactly(authorId);
        assertThat(server.data("GET", "/friends", author, null).findValuesAsText("userId")).containsExactly(ownerId);
    }

    private static void assertDissolved(Server server, String group, String room, String owner, String author, String invitee, String pendingInvite) throws Exception {
        for (String viewer : new String[]{owner, author}) {
            assertThat(server.data("GET", "/groups", viewer, null).findValuesAsText("id")).doesNotContain(group);
            for (String path : new String[]{"/groups/" + group, "/groups/" + group + "/members", "/groups/" + group + "/rooms",
                    "/rooms/" + room, "/groups/" + group + "/messages", "/groups/" + group + "/audit-logs", "/groups/" + group + "/invitations"}) {
                assertThat(server.call("GET", path, viewer, null).statusCode()).as("GET %s after dissolution", path).isEqualTo(404);
            }
        }
        assertThat(server.data("GET", "/invitations", invitee, null).findValuesAsText("id")).doesNotContain(pendingInvite);
        assertThat(server.call("POST", "/invitations/" + pendingInvite + "/accept", invitee, null).statusCode()).isEqualTo(404);
        assertThat(server.call("POST", "/groups/" + group + "/messages", owner, Map.of("body", "不能恢复群")).statusCode()).isEqualTo(404);
    }

    private record Server(Process process, URI entry) implements AutoCloseable {
        static Server start(Path jar, String database, Path log) throws Exception {
            var child = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-jar", jar.toString(),
                    "--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + database,
                    "--spring.datasource.username=postgres", "--spring.datasource.password=", "--zhijie.mail.enabled=false",
                    "--zhijie.voice.enabled=false", "--zhijie.scheduling.enabled=false")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean ready = false;
            try {
                var port = Pattern.compile("Tomcat started on port (\\d+) ");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
                while (child.isAlive() && System.nanoTime() < deadline) {
                    var match = port.matcher(Files.readString(log));
                    if (match.find()) {
                        var server = new Server(child, URI.create("http://127.0.0.1:" + match.group(1)));
                        var health = HTTP.send(HttpRequest.newBuilder(server.entry.resolve("/actuator/health")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
                        if (health.statusCode() == 200 && JSON.readTree(health.body()).path("status").asText().equals("UP")) {
                            ready = true;
                            return server;
                        }
                    }
                    Thread.sleep(150);
                }
                throw new AssertionError("Temporary packaged server did not become ready: " + log);
            } finally {
                if (!ready) stop(child);
            }
        }
        static void stop(Process child) throws InterruptedException {
            child.destroy();
            if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly();
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
        public void close() throws InterruptedException { stop(process); }
        HttpResponse<String> call(String method, String path, String token, Object body) throws Exception {
            var request = HttpRequest.newBuilder(entry.resolve("/api/v1" + path)).timeout(Duration.ofSeconds(8));
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (body != null) request.header("Content-Type", "application/json");
            return HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                    HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        JsonNode data(String method, String path, String token, Object body) throws Exception {
            var response = call(method, path, token, body);
            assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(200);
            return JSON.readTree(response.body()).required("data");
        }
        JsonNode login(String name, String password) throws Exception {
            return data("POST", "/auth/login", null, Map.of("email", name + "@example.test", "password", password, "deviceId", name));
        }
        void role(String group, String owner, String member, String role) throws Exception {
            data("PATCH", "/groups/" + group + "/members/" + member, owner, Map.of("role", role));
        }
        JsonNode moderationAudit(String group, String viewer, String moderator, String message) throws Exception {
            var history = data("GET", "/groups/" + group + "/audit-logs", viewer, null);
            var entries = history.valueStream().filter(row -> row.path("action").asText().equals("MESSAGE_DELETE")).toList();
            assertThat(entries).hasSize(1);
            var entry = entries.getFirst();
            assertThat(entry.required("actorId").asText()).isEqualTo(moderator);
            assertThat(entry.required("targetType").asText()).isEqualTo("MESSAGE");
            assertThat(entry.required("targetId").asText()).isEqualTo(message);
            assertThat(entry.required("result").asText()).isEqualTo("SUCCESS");
            assertThat(entry.required("occurredAt").asText()).isNotBlank();
            assertThat(entry.required("correlationId").asText()).isNotBlank();
            return entry;
        }
    }
}
