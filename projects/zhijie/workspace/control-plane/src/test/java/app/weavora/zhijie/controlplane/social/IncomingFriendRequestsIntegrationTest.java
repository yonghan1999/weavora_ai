package app.weavora.zhijie.controlplane.social;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.room.RoomService;
import app.weavora.zhijie.controlplane.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class IncomingFriendRequestsIntegrationTest {
    @Test
    void httpInboxIsPrivatePaginatedAndSupportsAcceptRejectWithoutLostConcurrentDecisions() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identities = context.getBean(IdentityRepository.class);
            var identity = context.getBean(IdentityService.class);
            var social = context.getBean(SocialService.class);
            var sql = context.getBean(JdbcTemplate.class);
            var json = context.getBean(ObjectMapper.class);
            String hash = context.getBean(CredentialProtector.class).hashPassword("integration-password-123");
            var users = new ArrayList<String>();
            for (int index = 0; index < 57; index++) {
                String id = UUID.randomUUID().toString();
                identities.insertUser(new UserAccount(id, "inbox-" + index + "@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
                users.add(id);
            }
            String receiver = users.get(0), outsider = users.get(56);
            String receiverToken = identity.login("inbox-0@example.test", "integration-password-123", "inbox-receiver").accessToken();
            String outsiderToken = identity.login("inbox-56@example.test", "integration-password-123", "inbox-outsider").accessToken();
            var requests = new ArrayList<FriendRequest>();
            for (int index = 1; index <= 54; index++) {
                var request = social.sendRequest(users.get(index), receiver);
                requests.add(request);
                // 相同时间戳专门验证稳定的 ID 次级排序与分页边界。
                sql.update("UPDATE friend_requests SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), request.id());
            }
            var privateRequest = social.sendRequest(users.get(55), outsider);
            social.sendRequest(receiver, users.get(55));
            String entry = "http://127.0.0.1:" + ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var http = HttpClient.newHttpClient();
            var first = get(http, entry, "/friend-requests", receiverToken);
            assertThat(first.statusCode()).isEqualTo(200);
            var page = json.readTree(first.body()).path("data");
            assertThat(page.size()).isEqualTo(50);
            assertThat(first.body()).doesNotContain(privateRequest.id(), "password", "accessToken", "addresseeId");
            List<String> expected = requests.stream().map(FriendRequest::id).sorted(Comparator.reverseOrder()).toList();
            assertThat(ids(page)).containsExactlyElementsOf(expected.subList(0, 50));
            String cursor = page.get(49).path("id").asText();
            var disabledRequest = requests.stream().skip(2).filter(item -> !item.id().equals(cursor)).findFirst().orElseThrow();
            sql.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", disabledRequest.requesterId());
            // 游标申请处理后仍能翻页，不会跳过或重复边界记录。
            assertThat(post(http, entry, cursor, "reject", receiverToken).statusCode()).isEqualTo(200);
            var second = get(http, entry, "/friend-requests?beforeId=" + cursor, receiverToken);
            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(ids(json.readTree(second.body()).path("data"))).containsExactlyElementsOf(expected.subList(50, 54));
            var foreignCursor = get(http, entry, "/friend-requests?beforeId=" + privateRequest.id(), receiverToken);
            assertThat(json.readTree(foreignCursor.body()).path("data")).isEmpty();
            assertThat(get(http, entry, "/friend-requests?beforeId=", receiverToken).statusCode()).isEqualTo(422);
            assertThat(get(http, entry, "/friend-requests", null).statusCode()).isBetween(400, 499);
            assertThat(ids(json.readTree(get(http, entry, "/friend-requests", outsiderToken).body()).path("data")))
                    .hasSize(1).contains(privateRequest.id());
            var visible = json.readTree(get(http, entry, "/friend-requests", receiverToken).body()).path("data");
            var allVisible = new ArrayList<JsonNode>(); visible.forEach(allVisible::add);
            json.readTree(second.body()).path("data").forEach(allVisible::add);
            assertThat(allVisible).anySatisfy(item -> {
                assertThat(item.path("id").asText()).isEqualTo(disabledRequest.id());
                assertThat(item.path("accountStatus").asText()).isEqualTo("DISABLED");
            });
            assertThat(post(http, entry, disabledRequest.id(), "accept", receiverToken).statusCode()).isEqualTo(403);
            assertThat(post(http, entry, disabledRequest.id(), "reject", receiverToken).statusCode()).isEqualTo(200);
            var candidates = requests.stream().filter(item -> !item.id().equals(cursor) && !item.id().equals(disabledRequest.id())).toList();
            var accepted = candidates.get(0);
            assertThat(post(http, entry, accepted.id(), "accept", outsiderToken).statusCode()).isEqualTo(403);
            assertThat(post(http, entry, accepted.id(), "accept", receiverToken).statusCode()).isEqualTo(200);
            assertThat(post(http, entry, accepted.id(), "reject", receiverToken).statusCode()).isEqualTo(409);
            assertThat(social.listFriends(receiver)).extracting(FriendView::userId).containsExactly(accepted.requesterId());
            var competing = candidates.get(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var accept = executor.submit(() -> post(http, entry, competing.id(), "accept", receiverToken).statusCode());
                var reject = executor.submit(() -> post(http, entry, competing.id(), "reject", receiverToken).statusCode());
                assertThat(List.of(accept.get(), reject.get())).containsExactlyInAnyOrder(200, 409);
            }
            String status = sql.queryForObject("SELECT status FROM friend_requests WHERE id = ?", String.class, competing.id());
            assertThat(social.listFriends(receiver).stream().anyMatch(friend -> friend.userId().equals(competing.requesterId())))
                    .isEqualTo(status.equals("ACCEPTED"));
            String requesterEmail = identities.findUserById(accepted.requesterId()).orElseThrow().emailNormalized();
            String requesterToken = identity.login(requesterEmail, "integration-password-123", "requester-http").accessToken();
            var friends = json.readTree(get(http, entry, "/friends", receiverToken).body()).path("data");
            assertThat(idsOfUsers(friends)).contains(accepted.requesterId());
            var group = context.getBean(GroupService.class).createGroup(accepted.requesterId(), "好友私有群", null, "");
            var rooms = context.getBean(RoomService.class);
            var room = rooms.createRoom(accepted.requesterId(), group.id(), "好友私有房间", "", "测试游戏", null);
            rooms.startActivity(accepted.requesterId(), room.id());
            assertThat(get(http, entry, "/groups/" + group.id(), requesterToken).statusCode()).isEqualTo(200);
            assertThat(get(http, entry, "/rooms/" + room.id(), requesterToken).statusCode()).isEqualTo(200);
            var join = http.send(HttpRequest.newBuilder(URI.create(entry + "/api/v1/room-joins"))
                    .header("Authorization", "Bearer " + receiverToken).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("roomId", room.id(),
                            "deviceId", "inbox-receiver", "operationId", UUID.randomUUID().toString())))).build(), HttpResponse.BodyHandlers.ofString());
            for (var denied : List.of(get(http, entry, "/groups/" + group.id(), receiverToken),
                    get(http, entry, "/rooms/" + room.id(), receiverToken), join)) {
                assertThat(denied.statusCode()).isEqualTo(404);
                assertThat(json.readTree(denied.body()).path("error").path("code").asText()).isEqualTo("RESOURCE_NOT_AVAILABLE");
                assertThat(denied.body()).doesNotContain(group.id(), room.id(), "好友私有");
            }
            assertThat(context.getBean(ActivityRepository.class).findAccountActivity(receiver)).isEmpty();
            assertThat(http.send(HttpRequest.newBuilder(URI.create(entry + "/api/v1/friends/" + receiver))
                    .header("Authorization", "Bearer " + requesterToken).DELETE().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            assertThat(idsOfUsers(json.readTree(get(http, entry, "/friends", receiverToken).body()).path("data"))).doesNotContain(accepted.requesterId());
            assertThat(get(http, entry, "/users/search?query=" + requesterEmail, receiverToken).body()).contains(accepted.requesterId());
            var resend = http.send(HttpRequest.newBuilder(URI.create(entry + "/api/v1/friend-requests"))
                    .header("Authorization", "Bearer " + requesterToken).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("addresseeId", receiver)))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(resend.statusCode()).isEqualTo(200);
            String resentId = json.readTree(resend.body()).path("data").path("id").asText();
            assertThat(ids(json.readTree(get(http, entry, "/friend-requests", receiverToken).body()).path("data"))).contains(resentId);
            assertThat(post(http, entry, resentId, "reject", receiverToken).statusCode()).isEqualTo(200);
            assertThat(ids(json.readTree(get(http, entry, "/friend-requests", receiverToken).body()).path("data"))).doesNotContain(resentId);
            sql.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", receiver);
            assertThat(get(http, entry, "/friend-requests", receiverToken).statusCode()).isEqualTo(403);
        }
    }

    private static List<String> ids(JsonNode page) {
        var ids = new ArrayList<String>();
        page.forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private static List<String> idsOfUsers(JsonNode page) {
        var ids = new ArrayList<String>();
        page.forEach(item -> ids.add(item.path("userId").asText()));
        return ids;
    }

    private static HttpResponse<String> get(HttpClient http, String entry, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(entry + "/api/v1" + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient http, String entry, String id, String action, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(entry + "/api/v1/friend-requests/" + id + "/" + action))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
