package app.weavora.zhijie.controlplane.group;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.identity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class OwnerTransferIntegrationTest {
    @Test
    void transferDiscoveryAcceptanceCancellationAndConcurrentDecisionsPreserveOneOwner() throws Exception {
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
            for (int index = 0; index < 4; index++) {
                String id = UUID.randomUUID().toString(), email = "owner-transfer-" + index + "@example.test";
                identities.insertUser(new UserAccount(id, email, AccountStatus.ACTIVE, hash, Instant.now()));
                users.add(id);
                tokens.add(identity.login(email, "integration-password-123", "transfer-device").accessToken());
            }
            String owner = users.get(0), target = users.get(1), member = users.get(2);
            Group group = groups.createGroup(owner, "转让验证", null, "");
            for (String user : List.of(target, member)) groups.acceptInvitation(user, groups.invite(owner, group.id(), user).id());
            String entry = "http://127.0.0.1:" + ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var http = HttpClient.newHttpClient();
            String base = "/groups/" + group.id(), transfers = base + "/owner-transfers";
            assertThat(send(http, entry, "GET", transfers, tokens.get(3), null).statusCode()).isEqualTo(404);
            assertThat(send(http, entry, "POST", transfers, tokens.get(1), json.writeValueAsString(Map.of("targetUserId", member))).statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "POST", transfers, tokens.get(0), json.writeValueAsString(Map.of("targetUserId", owner))).statusCode()).isEqualTo(409);
            String transfer = create(http, entry, transfers, tokens.get(0), target, json);
            assertOwner(repository, group.id(), owner);
            for (int index : List.of(0, 1)) {
                var response = send(http, entry, "GET", transfers, tokens.get(index), null);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(json.readTree(response.body()).path("data").get(0).path("id").asText()).isEqualTo(transfer);
                assertThat(response.body()).doesNotContain("password", "accessToken");
            }
            assertThat(json.readTree(send(http, entry, "GET", transfers, tokens.get(2), null).body()).path("data")).isEmpty();
            assertThat(send(http, entry, "POST", transfers, tokens.get(0), json.writeValueAsString(Map.of("targetUserId", member))).statusCode()).isEqualTo(409);
            assertThat(send(http, entry, "POST", base + "/leave", tokens.get(0), "{}").statusCode()).isEqualTo(409);
            assertThat(decide(http, entry, transfer, "accept", tokens.get(2))).isEqualTo(403);
            assertThat(decide(http, entry, transfer, "cancel", tokens.get(1))).isEqualTo(403);
            assertThat(decide(http, entry, transfer, "cancel", tokens.get(0))).isEqualTo(200);
            assertThat(decide(http, entry, transfer, "accept", tokens.get(1))).isEqualTo(404);
            assertOwner(repository, group.id(), owner);

            transfer = create(http, entry, transfers, tokens.get(0), target, json);
            assertThat(decide(http, entry, transfer, "accept", tokens.get(1))).isEqualTo(200);
            assertOwner(repository, group.id(), target);

            Group unavailable = groups.createGroup(owner, "接收人失效", null, "");
            groups.acceptInvitation(member, groups.invite(owner, unavailable.id(), member).id());
            String unavailableId = groups.initiateTransfer(owner, unavailable.id(), member).id();
            sql.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", member);
            assertThat(decide(http, entry, unavailableId, "accept", tokens.get(2))).isEqualTo(403);
            assertOwner(repository, unavailable.id(), owner);
            sql.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", member);
            groups.removeMember(owner, unavailable.id(), member);
            assertThat(repository.findPendingTransfer(unavailable.id())).isEmpty();
            groups.acceptInvitation(member, groups.invite(owner, unavailable.id(), member).id());
            assertThat(decide(http, entry, unavailableId, "accept", tokens.get(2))).isEqualTo(404);
            String dissolvedId = groups.initiateTransfer(owner, unavailable.id(), member).id();
            groups.dissolveGroup(owner, unavailable.id());
            assertThat(decide(http, entry, dissolvedId, "accept", tokens.get(2))).isEqualTo(404);
            assertThat(repository.findMember(group.id(), owner).orElseThrow().role()).isEqualTo(GroupRole.MEMBER);
            assertThat(send(http, entry, "PATCH", base, tokens.get(0), "{\"name\":\"旧群主\"}").statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "POST", base + "/leave", tokens.get(0), "{}").statusCode()).isEqualTo(200);

            // 指定接收人离群会释放待处理槽位；重新入群也不能接受旧转让。
            String departedTransfer = create(http, entry, transfers, tokens.get(1), member, json);
            assertThat(send(http, entry, "POST", base + "/leave", tokens.get(2), "{}").statusCode()).isEqualTo(200);
            assertThat(repository.findPendingTransfer(group.id())).isEmpty();
            groups.acceptInvitation(member, groups.invite(target, group.id(), member).id());
            assertThat(decide(http, entry, departedTransfer, "accept", tokens.get(2))).isEqualTo(404);
            var recovery = send(http, entry, "POST", base + "/owner-recovery-requests", tokens.get(2), "{\"evidenceRef\":\"support-ticket-123\"}");
            assertThat(recovery.statusCode()).isEqualTo(200);
            assertThat(json.readTree(recovery.body()).path("data").path("status").asText()).isEqualTo("PENDING");
            assertOwner(repository, group.id(), target);

            try (var executor = Executors.newFixedThreadPool(2)) {
                for (int round = 0; round < 3; round++) {
                    Group race = groups.createGroup(owner, "并发转让", null, "");
                    groups.acceptInvitation(target, groups.invite(owner, race.id(), target).id());
                    String racePath = "/groups/" + race.id() + "/owner-transfers";
                    var start = new CountDownLatch(1);
                    Callable<HttpResponse<String>> initiate = () -> { start.await(); return send(http, entry, "POST", racePath, tokens.get(0), json.writeValueAsString(Map.of("targetUserId", target))); };
                    var first = executor.submit(initiate); var second = executor.submit(initiate); start.countDown();
                    var responses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                    assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
                    String raceId = json.readTree(responses.stream().filter(response -> response.statusCode() == 200).findFirst().orElseThrow().body()).path("data").path("id").asText();
                    var decisionStart = new CountDownLatch(1);
                    var accept = executor.submit(() -> { decisionStart.await(); return decide(http, entry, raceId, "accept", tokens.get(1)); });
                    var cancel = executor.submit(() -> { decisionStart.await(); return decide(http, entry, raceId, "cancel", tokens.get(0)); });
                    decisionStart.countDown();
                    assertThat(List.of(accept.get(10, TimeUnit.SECONDS), cancel.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 404);
                    String status = sql.queryForObject("SELECT status FROM owner_transfers WHERE id = ?", String.class, raceId);
                    assertOwner(repository, race.id(), status.equals("ACCEPTED") ? target : owner);
                    assertThat(repository.findPendingTransfer(race.id())).isEmpty();

                    Group removalRace = groups.createGroup(owner, "转让与移除竞争", null, "");
                    groups.acceptInvitation(target, groups.invite(owner, removalRace.id(), target).id());
                    String removalId = groups.initiateTransfer(owner, removalRace.id(), target).id();
                    var removalStart = new CountDownLatch(1);
                    var raceAccept = executor.submit(() -> { removalStart.await(); return decide(http, entry, removalId, "accept", tokens.get(1)); });
                    var remove = executor.submit(() -> { removalStart.await(); return send(http, entry, "DELETE", "/groups/" + removalRace.id() + "/members/" + target, tokens.get(0), null).statusCode(); });
                    removalStart.countDown();
                    int acceptStatus = raceAccept.get(10, TimeUnit.SECONDS), removeStatus = remove.get(10, TimeUnit.SECONDS);
                    if (acceptStatus == 200) {
                        assertThat(removeStatus).isEqualTo(403);
                        assertOwner(repository, removalRace.id(), target);
                    } else {
                        assertThat(acceptStatus).isEqualTo(404);
                        assertThat(removeStatus).isEqualTo(200);
                        assertOwner(repository, removalRace.id(), owner);
                    }
                    assertThat(repository.findPendingTransfer(removalRace.id())).isEmpty();
                }
            }
        }
    }

    private static void assertOwner(GroupRepository repository, String group, String owner) {
        assertThat(repository.findGroup(group).orElseThrow().ownerUserId()).isEqualTo(owner);
        assertThat(repository.listActiveMembers(group).stream().filter(member -> member.role() == GroupRole.OWNER).map(GroupMember::userId).toList()).containsExactly(owner);
    }

    private static String create(HttpClient http, String entry, String path, String token, String target, ObjectMapper json) throws Exception {
        var response = send(http, entry, "POST", path, token, json.writeValueAsString(Map.of("targetUserId", target)));
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data").path("id").asText();
    }

    private static int decide(HttpClient http, String entry, String transfer, String decision, String token) throws Exception {
        return send(http, entry, "POST", "/owner-transfers/" + transfer + "/" + decision, token, "{}").statusCode();
    }

    private static HttpResponse<String> send(HttpClient http, String entry, String method, String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(entry + "/api/v1" + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
