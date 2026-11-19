package app.weavora.zhijie.controlplane.room;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.*;
import app.weavora.zhijie.controlplane.grant.*;
import app.weavora.zhijie.controlplane.group.*;
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

class RoomGovernanceIntegrationTest {
    @Test
    void currentRoomRolesAndMemberGenerationsControlKicksWithoutBanningRejoin() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identities = context.getBean(IdentityRepository.class);
            var identity = context.getBean(IdentityService.class);
            var groups = context.getBean(GroupService.class);
            var rooms = context.getBean(RoomService.class);
            var coordinator = context.getBean(ActivityCoordinator.class);
            var activities = context.getBean(ActivityRepository.class);
            var sql = context.getBean(JdbcTemplate.class);
            var json = context.getBean(ObjectMapper.class);
            String hash = context.getBean(CredentialProtector.class).hashPassword("integration-password-123");
            var users = new ArrayList<String>(); var tokens = new ArrayList<String>();
            for (int index = 0; index < 7; index++) {
                String id = UUID.randomUUID().toString(), email = "room-governance-" + index + "@example.test";
                identities.insertUser(new UserAccount(id, email, AccountStatus.ACTIVE, hash, Instant.now()));
                users.add(id); tokens.add(identity.login(email, "integration-password-123", "room-governance-device").accessToken());
            }
            String owner = users.get(0), admin = users.get(1), peerAdmin = users.get(2), host = users.get(3), member = users.get(4), other = users.get(5), outsider = users.get(6);
            Group group = groups.createGroup(owner, "房间治理", null, "");
            for (String user : users.subList(1, 6)) groups.acceptInvitation(user, groups.invite(owner, group.id(), user).id());
            groups.changeRole(owner, group.id(), admin, GroupRole.ADMIN);
            groups.changeRole(owner, group.id(), peerAdmin, GroupRole.ADMIN);
            Room room = rooms.createRoom(owner, group.id(), "治理房间", "", "", "current-password-123");
            Activity activity = rooms.startActivity(owner, room.id());
            String entry = "http://127.0.0.1:" + ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var http = HttpClient.newHttpClient();
            String roomPath = "/rooms/" + room.id();
            String hostBody = json.writeValueAsString(Map.of("targetUserId", host));
            assertThat(send(http, entry, "POST", roomPath + "/owner", tokens.get(1), hostBody).statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "POST", roomPath + "/owner", tokens.get(0), json.writeValueAsString(Map.of("targetUserId", outsider))).statusCode()).isEqualTo(404);
            assertThat(send(http, entry, "POST", roomPath + "/owner", tokens.get(0), hostBody).statusCode()).isEqualTo(200);
            sql.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", other);
            assertThat(send(http, entry, "POST", roomPath + "/owner", tokens.get(0), json.writeValueAsString(Map.of("targetUserId", other))).statusCode()).isEqualTo(403);
            sql.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", other);
            var joined = new ArrayList<JoinResult>();
            for (String user : users.subList(0, 6)) joined.add(coordinator.join(user, room.id(), "device-" + user, "current-password-123", "join-" + user));
            String memberKick = "/activities/" + activity.id() + "/members/" + member + "/kick";
            String generation = json.writeValueAsString(Map.of("memberGeneration", joined.get(4).memberGeneration()));
            assertThat(send(http, entry, "POST", memberKick, tokens.get(5), generation).statusCode()).isEqualTo(403);
            for (int index : List.of(0, 1, 2)) {
                String protectedKick = "/activities/" + activity.id() + "/members/" + users.get(index) + "/kick";
                assertThat(send(http, entry, "POST", protectedKick, tokens.get(3), json.writeValueAsString(Map.of("memberGeneration", joined.get(index).memberGeneration()))).statusCode()).isEqualTo(403);
            }
            assertThat(send(http, entry, "POST", "/activities/" + activity.id() + "/members/" + peerAdmin + "/kick", tokens.get(1), "{\"memberGeneration\":1}").statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "POST", "/activities/" + activity.id() + "/members/" + host + "/kick", tokens.get(3), "{\"memberGeneration\":1}").statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "POST", memberKick, tokens.get(0), "{\"memberGeneration\":0}").statusCode()).isEqualTo(422);
            byte[] key = new byte[32]; Arrays.fill(key, (byte) 7);
            var grant = context.getBean(GrantService.class).network(member, activity.id(), Base64.getEncoder().encodeToString(key));
            assertThat(send(http, entry, "POST", memberKick, tokens.get(3), generation).statusCode()).isEqualTo(200);
            assertThat(activities.findMember(activity.id(), member).orElseThrow().state()).isEqualTo(MemberState.REVOKED);
            assertThat(activities.findLatestLease(activity.id(), member).orElseThrow().revokedAt()).isNotNull();
            assertThat(activities.findAccountActivity(member)).isEmpty();
            assertThat(activities.findAddress(activity.id(), member).orElseThrow().releasedAt()).isNotNull();
            assertThat(context.getBean(GrantRepository.class).findById(grant.grantId()).orElseThrow().revokedAt()).isNotNull();
            assertThat(context.getBean(GroupRepository.class).findMember(group.id(), member).orElseThrow().status()).isEqualTo(MemberStatus.ACTIVE);
            assertThat(send(http, entry, "POST", "/activities/" + activity.id() + "/resume", tokens.get(4), json.writeValueAsString(Map.of("deviceId", "device-" + member))).statusCode()).isEqualTo(409);
            assertThat(send(http, entry, "GET", "/activities/" + activity.id() + "/members", tokens.get(4), null).statusCode()).isEqualTo(404);
            JoinResult rejoined = coordinator.join(member, room.id(), "device-" + member, "current-password-123", "rejoin");
            assertThat(rejoined.memberGeneration()).isGreaterThan(joined.get(4).memberGeneration());
            assertThat(send(http, entry, "POST", memberKick, tokens.get(3), generation).statusCode()).isEqualTo(409);
            assertThat(activities.findMember(activity.id(), member).orElseThrow().state()).isEqualTo(MemberState.ACTIVE);
            coordinator.disconnect(member, activity.id(), "device-" + member);
            assertThat(send(http, entry, "POST", memberKick, tokens.get(1), json.writeValueAsString(Map.of("memberGeneration", rejoined.memberGeneration()))).statusCode()).isEqualTo(200);
            groups.changeRole(owner, group.id(), admin, GroupRole.MEMBER);
            assertThat(send(http, entry, "POST", "/activities/" + activity.id() + "/members/" + other + "/kick", tokens.get(1), "{\"memberGeneration\":1}").statusCode()).isEqualTo(403);
            rooms.assignOwner(owner, room.id(), other);
            assertThat(send(http, entry, "POST", memberKick, tokens.get(3), json.writeValueAsString(Map.of("memberGeneration", rejoined.memberGeneration()))).statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "PATCH", roomPath, tokens.get(3), "{\"name\":\"旧房主页面\"}").statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "POST", "/groups/" + group.id() + "/rooms", tokens.get(5), "{\"name\":\"越权创建\"}").statusCode()).isEqualTo(403);
            coordinator.leave(other, activity.id());
            assertThat(context.getBean(RoomRepository.class).findRoom(room.id()).orElseThrow().ownerUserId()).isEqualTo(other);
            groups.removeMember(owner, group.id(), other);
            assertThat(context.getBean(RoomRepository.class).findRoom(room.id()).orElseThrow().ownerUserId()).isEqualTo(owner);
            assertThat(sql.queryForObject("SELECT count(*) FROM audit_logs WHERE group_id = ? AND action = 'ACTIVITY_MEMBER_KICK'", Integer.class, group.id())).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT count(*) FROM audit_logs WHERE group_id = ? AND action = 'ROOM_OWNER_ASSIGN'", Integer.class, group.id())).isEqualTo(2);
            assertThat(send(http, entry, "GET", "/groups/" + group.id() + "/audit-logs", tokens.get(0), null).body()).contains("ACTIVITY_MEMBER_KICK", "ROOM_OWNER_ASSIGN");
            assertThat(send(http, entry, "GET", "/groups/" + group.id() + "/audit-logs", tokens.get(3), null).statusCode()).isEqualTo(403);
            coordinator.leave(host, activity.id());

            try (var executor = Executors.newFixedThreadPool(2)) {
                for (int round = 0; round < 3; round++) {
                    Room raceRoom = rooms.createRoom(owner, group.id(), "并发治理-" + round, "", "", null);
                    Activity raceActivity = rooms.startActivity(owner, raceRoom.id());
                    JoinResult first = coordinator.join(member, raceRoom.id(), "old-device", null, "old-join");
                    String raceKick = "/activities/" + raceActivity.id() + "/members/" + member + "/kick";
                    String oldGeneration = json.writeValueAsString(Map.of("memberGeneration", first.memberGeneration()));
                    var start = new CountDownLatch(1);
                    var kick = executor.submit(() -> { start.await(); return send(http, entry, "POST", raceKick, tokens.get(0), oldGeneration).statusCode(); });
                    var replacement = executor.submit(() -> { start.await(); return coordinator.join(member, raceRoom.id(), "new-device", null, "new-join"); });
                    start.countDown();
                    assertThat(kick.get(10, TimeUnit.SECONDS)).isIn(200, 409);
                    JoinResult latest = replacement.get(10, TimeUnit.SECONDS);
                    ActivityMember current = activities.findMember(raceActivity.id(), member).orElseThrow();
                    assertThat(current.state()).isEqualTo(MemberState.ACTIVE);
                    assertThat(current.memberGeneration()).isEqualTo(latest.memberGeneration()).isGreaterThan(first.memberGeneration());
                    assertThat(activities.findLatestLease(raceActivity.id(), member).orElseThrow().revokedAt()).isNull();

                    var endingStart = new CountDownLatch(1);
                    var ending = executor.submit(() -> { endingStart.await(); rooms.endActivity(owner, raceActivity.id()); return true; });
                    var joining = executor.submit(() -> {
                        endingStart.await();
                        return send(http, entry, "POST", "/room-joins", tokens.get(3),
                                json.writeValueAsString(Map.of("roomId", raceRoom.id(), "deviceId", "ending-race-device", "operationId", "ending-race")));
                    });
                    endingStart.countDown();
                    assertThat(ending.get(10, TimeUnit.SECONDS)).isTrue();
                    HttpResponse<String> joinedDuringEnd = joining.get(10, TimeUnit.SECONDS);
                    assertThat(joinedDuringEnd.statusCode()).isIn(200, 409);
                    if (joinedDuringEnd.statusCode() == 409) assertThat(joinedDuringEnd.body()).contains("ACTIVITY_NOT_ACTIVE");
                    assertThat(activities.listMembers(raceActivity.id())).noneMatch(item -> item.state() == MemberState.ACTIVE || item.state() == MemberState.GRACE);
                    assertThat(activities.findAccountActivity(member)).isEmpty();
                    assertThat(activities.findAccountActivity(host)).isEmpty();
                }
            }
        }
    }

    private static HttpResponse<String> send(HttpClient http, String entry, String method, String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(entry + "/api/v1" + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
