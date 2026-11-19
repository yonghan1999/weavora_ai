package app.weavora.zhijie.controlplane.group;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class GroupInvitationsIntegrationTest {
    @Test
    void invitationInboxesArePrivatePaginatedAndHonorRevocationMembershipAndConcurrentDecisions() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identities = context.getBean(IdentityRepository.class);
            var identity = context.getBean(IdentityService.class);
            var groups = context.getBean(GroupService.class);
            var repository = context.getBean(GroupRepository.class);
            var sql = context.getBean(JdbcTemplate.class);
            var json = context.getBean(ObjectMapper.class);
            String hash = context.getBean(CredentialProtector.class).hashPassword("integration-password-123");
            var users = new ArrayList<String>();
            var tokens = new ArrayList<String>();
            for (int index = 0; index < 56; index++) {
                String id = UUID.randomUUID().toString(), email = "invitation-" + index + "@example.test";
                identities.insertUser(new UserAccount(id, email, AccountStatus.ACTIVE, hash, Instant.now()));
                users.add(id);
                if (index < 4) tokens.add(identity.login(email, "integration-password-123", "invitation-device").accessToken());
            }
            String owner = users.get(0), receiver = users.get(1), member = users.get(2), outsider = users.get(3);
            Group group = groups.createGroup(owner, "邀请测试 <&>", null, "");
            groups.acceptInvitation(member, groups.invite(owner, group.id(), member).id());
            var sent = new ArrayList<GroupInvitation>();
            for (int index = 4; index < users.size(); index++) sent.add(groups.invite(member, group.id(), users.get(index)));
            var incoming = new ArrayList<GroupInvitation>();
            for (int index = 0; index < 52; index++) {
                Group source = groups.createGroup(owner, "收件群 " + index, null, "");
                incoming.add(groups.invite(owner, source.id(), receiver));
            }
            var foreign = groups.invite(owner, group.id(), outsider);
            var timestamp = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
            for (var invitation : incoming) sql.update("UPDATE invitations SET created_at = ? WHERE id = ?", timestamp, invitation.id());
            for (var invitation : sent) sql.update("UPDATE invitations SET created_at = ? WHERE id = ?", timestamp, invitation.id());
            String entry = "http://127.0.0.1:" + ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var http = HttpClient.newHttpClient();
            String managerPath = "/groups/" + group.id() + "/invitations";
            var first = send(http, entry, "GET", "/invitations", tokens.get(1), null);
            assertThat(first.statusCode()).isEqualTo(200);
            List<String> expected = incoming.stream().map(GroupInvitation::id).sorted(Comparator.reverseOrder()).toList();
            assertThat(ids(json.readTree(first.body()).path("data"))).containsExactlyElementsOf(expected.subList(0, 50));
            assertThat(first.body()).doesNotContain(foreign.id(), "password", "accessToken");
            String cursor = expected.get(49);
            assertThat(send(http, entry, "POST", "/invitations/" + cursor + "/accept", tokens.get(1), "{}").statusCode()).isEqualTo(200);
            assertThat(ids(data(json, send(http, entry, "GET", "/invitations?beforeId=" + cursor, tokens.get(1), null)))).containsExactlyElementsOf(expected.subList(50, 52));
            assertThat(ids(data(json, send(http, entry, "GET", "/invitations?beforeId=" + foreign.id(), tokens.get(1), null)))).isEmpty();
            assertThat(send(http, entry, "GET", "/invitations?beforeId=", tokens.get(1), null).statusCode()).isEqualTo(422);
            assertThat(send(http, entry, "GET", managerPath, tokens.get(2), null).statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "GET", managerPath, tokens.get(3), null).statusCode()).isEqualTo(404);
            groups.changeRole(owner, group.id(), member, GroupRole.ADMIN);
            var managerPage = data(json, send(http, entry, "GET", managerPath, tokens.get(2), null));
            assertThat(managerPage.size()).isEqualTo(50);
            String managerCursor = managerPage.get(49).path("id").asText();
            assertThat(send(http, entry, "DELETE", managerPath + "/" + managerCursor, tokens.get(2), null).statusCode()).isEqualTo(200);
            assertThat(data(json, send(http, entry, "GET", managerPath + "?beforeId=" + managerCursor, tokens.get(2), null)).size()).isEqualTo(3);
            assertThat(data(json, send(http, entry, "GET", managerPath + "?beforeId=" + cursor, tokens.get(2), null))).isEmpty();
            groups.changeRole(owner, group.id(), member, GroupRole.MEMBER);
            assertThat(send(http, entry, "DELETE", managerPath + "/" + foreign.id(), tokens.get(2), null).statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "POST", managerPath, tokens.get(2), json.writeValueAsString(Map.of("inviteeId", outsider))).statusCode()).isEqualTo(409);
            assertThat(send(http, entry, "POST", "/invitations/" + foreign.id() + "/accept", tokens.get(1), "{}").statusCode()).isEqualTo(404);
            assertThat(send(http, entry, "DELETE", managerPath + "/" + foreign.id(), tokens.get(0), null).statusCode()).isEqualTo(200);
            assertThat(send(http, entry, "POST", "/invitations/" + foreign.id() + "/accept", tokens.get(3), "{}").statusCode()).isEqualTo(404);
            assertThat(ids(data(json, send(http, entry, "GET", "/invitations", tokens.get(3), null)))).doesNotContain(foreign.id());

            // 被禁用邀请人的邀请暂不可接受，管理者仍可发现并撤销。
            sql.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", member);
            String sentRecipient = sent.stream().filter(invitation -> !invitation.id().equals(managerCursor)).findFirst().orElseThrow().inviteeId();
            assertThat(groups.listIncomingInvitations(sentRecipient, null)).isEmpty();
            assertThat(groups.listGroupInvitations(owner, group.id(), null)).isNotEmpty();
            sql.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", member);
            groups.leaveGroup(member, group.id());
            assertThat(repository.findInvitation(sent.get(0).id()).orElseThrow().status()).isIn(InvitationStatus.INVALIDATED, InvitationStatus.REVOKED);
            assertThat(groups.listGroupInvitations(owner, group.id(), null)).isEmpty();

            try (var executor = Executors.newFixedThreadPool(2)) {
                for (int round = 0; round < 3; round++) {
                    var invitation = groups.invite(owner, group.id(), outsider);
                    var start = new CountDownLatch(1);
                    var accept = executor.submit(() -> { start.await(); return send(http, entry, "POST", "/invitations/" + invitation.id() + "/accept", tokens.get(3), "{}").statusCode(); });
                    var revoke = executor.submit(() -> { start.await(); return send(http, entry, "DELETE", managerPath + "/" + invitation.id(), tokens.get(0), null).statusCode(); });
                    start.countDown();
                    assertThat(List.of(accept.get(10, TimeUnit.SECONDS), revoke.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 404);
                    var finalInvitation = repository.findInvitation(invitation.id()).orElseThrow();
                    boolean joined = repository.findMember(group.id(), outsider).filter(item -> item.status() == MemberStatus.ACTIVE).isPresent();
                    assertThat(joined).isEqualTo(finalInvitation.status() == InvitationStatus.ACCEPTED);
                    if (joined) {
                        assertThat(send(http, entry, "POST", "/invitations/" + invitation.id() + "/accept", tokens.get(3), "{}").statusCode()).isEqualTo(200);
                        groups.leaveGroup(outsider, group.id());
                        // 已接受邀请不能在离群后重复恢复成员身份。
                        assertThat(send(http, entry, "POST", "/invitations/" + invitation.id() + "/accept", tokens.get(3), "{}").statusCode()).isEqualTo(404);
                        assertThat(repository.findMember(group.id(), outsider).orElseThrow().status()).isEqualTo(MemberStatus.LEFT);
                    }
                }
            }
            var firstJoin = groups.invite(owner, group.id(), outsider);
            String oldAccept = "/invitations/" + firstJoin.id() + "/accept";
            assertThat(send(http, entry, "POST", oldAccept, tokens.get(3), "{}").statusCode()).isEqualTo(200);
            groups.changeRole(owner, group.id(), outsider, GroupRole.ADMIN);
            assertThat(send(http, entry, "DELETE", "/groups/" + group.id() + "/members/" + outsider, tokens.get(0), null).statusCode()).isEqualTo(200);
            assertThat(send(http, entry, "POST", oldAccept, tokens.get(3), "{}").statusCode()).isEqualTo(404);
            assertThat(repository.findMember(group.id(), outsider).orElseThrow().status()).isEqualTo(MemberStatus.REMOVED);
            String newAccept = "/invitations/" + groups.invite(owner, group.id(), outsider).id() + "/accept";
            assertThat(send(http, entry, "POST", newAccept, tokens.get(3), "{}").statusCode()).isEqualTo(200);
            assertThat(send(http, entry, "POST", newAccept, tokens.get(3), "{}").statusCode()).isEqualTo(200);
            assertThat(send(http, entry, "POST", oldAccept, tokens.get(3), "{}").statusCode()).isEqualTo(404);
            var previous = repository.findInvitation(firstJoin.id()).orElseThrow();
            assertThat(previous.status()).isEqualTo(InvitationStatus.ACCEPTED);
            assertThat(previous.invalidatedAt()).isNotNull();
            assertThat(repository.findMember(group.id(), outsider).orElseThrow().role()).isEqualTo(GroupRole.MEMBER);
            groups.leaveGroup(outsider, group.id());
            var dissolved = groups.invite(owner, group.id(), outsider);
            groups.dissolveGroup(owner, group.id());
            assertThat(ids(data(json, send(http, entry, "GET", "/invitations", tokens.get(3), null)))).doesNotContain(dissolved.id());
            assertThat(send(http, entry, "GET", managerPath, tokens.get(0), null).statusCode()).isEqualTo(404);
        }
    }

    private static List<String> ids(JsonNode page) {
        var result = new ArrayList<String>(); page.forEach(item -> result.add(item.path("id").asText())); return result;
    }
    private static JsonNode data(ObjectMapper json, HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200); return json.readTree(response.body()).path("data");
    }
    private static HttpResponse<String> send(HttpClient http, String entry, String method, String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(entry + "/api/v1" + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
