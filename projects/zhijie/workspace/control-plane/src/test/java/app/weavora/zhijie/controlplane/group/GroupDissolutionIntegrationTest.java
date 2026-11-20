package app.weavora.zhijie.controlplane.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.*;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.*;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.network.*;
import app.weavora.zhijie.controlplane.room.*;
import app.weavora.zhijie.controlplane.sync.*;
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

class GroupDissolutionIntegrationTest {
    @Test
    void dissolutionRevokesEveryActivityAndRejectsOldCapabilitiesWithoutAffectingOtherGroups() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--zhijie.voice.enabled=true", "--zhijie.voice.url=wss://voice.example.test", "--zhijie.voice.api-key=dissolution-test",
                "--zhijie.voice.api-secret=controlled-dissolution-test-secret-123456789")) {
            var identities = context.getBean(IdentityRepository.class);
            var identity = context.getBean(IdentityService.class);
            var groups = context.getBean(GroupService.class);
            var rooms = context.getBean(RoomService.class);
            var roomRepository = context.getBean(RoomRepository.class);
            var coordinator = context.getBean(ActivityCoordinator.class);
            var activities = context.getBean(ActivityRepository.class);
            var grants = context.getBean(GrantService.class);
            var sql = context.getBean(JdbcTemplate.class);
            var json = context.getBean(ObjectMapper.class);
            String hash = context.getBean(CredentialProtector.class).hashPassword("dissolution-password-123");
            var users = new ArrayList<String>(); var tokens = new ArrayList<String>();
            for (int index = 0; index < 5; index++) {
                String id = UUID.randomUUID().toString(), email = "dissolution-" + index + "@example.test";
                identities.insertUser(new UserAccount(id, email, AccountStatus.ACTIVE, hash, Instant.now()));
                users.add(id); tokens.add(identity.login(email, "dissolution-password-123", List.of("owner-device", "admin-device", "member-device", "outside-device", "invitee-device").get(index)).accessToken());
            }
            String owner = users.get(0), admin = users.get(1), member = users.get(2), outsider = users.get(3), invitee = users.get(4);
            Group group = groups.createGroup(owner, "解散群", null, "");
            for (String user : List.of(admin, member)) groups.acceptInvitation(user, groups.invite(owner, group.id(), user).id());
            groups.changeRole(owner, group.id(), admin, GroupRole.ADMIN);
            GroupInvitation pending = groups.invite(owner, group.id(), invitee);
            OwnerTransfer transfer = groups.initiateTransfer(owner, group.id(), admin);
            Room firstRoom = rooms.createRoom(owner, group.id(), "活动一", "", "", null);
            Room secondRoom = rooms.createRoom(owner, group.id(), "活动二", "", "", null);
            Activity first = rooms.startActivity(owner, firstRoom.id()), second = rooms.startActivity(owner, secondRoom.id());
            JoinResult ownerJoin = AuthenticatedActivities.join(context, owner, firstRoom.id(), "owner-device", null, "owner-join");
            JoinResult memberJoin = AuthenticatedActivities.join(context, member, firstRoom.id(), "member-device", null, "member-join");
            AuthenticatedActivities.join(context, admin, secondRoom.id(), "admin-device", null, "admin-join");
            coordinator.disconnect(member, first.id(), "member-device");
            byte[] key = new byte[32]; Arrays.fill(key, (byte) 7);
            String publicKey = Base64.getEncoder().encodeToString(key);
            var network = grants.network(member, first.id(), memberJoin.deviceId(), publicKey);
            var voice = grants.voice(member, first.id(), memberJoin.deviceId());
            context.getBean(NetworkAccessService.class).validate(network.networkTicket());
            context.getBean(VoiceAccessValidator.class).validate(voice.voiceToken());

            Group unaffected = groups.createGroup(outsider, "保留群", null, "");
            Room unaffectedRoom = rooms.createRoom(outsider, unaffected.id(), "保留房", "", "", null);
            Activity unaffectedActivity = rooms.startActivity(outsider, unaffectedRoom.id());
            AuthenticatedActivities.join(context, outsider, unaffectedRoom.id(), "outside-device", null, "outside-join");
            var unaffectedNetwork = grants.network(outsider, unaffectedActivity.id(), "outside-device", publicKey);
            String entry = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var http = HttpClient.newHttpClient();
            String dissolvePath = "/groups/" + group.id() + "/dissolve";
            assertThat(send(http, entry, "POST", dissolvePath, tokens.get(1), "{}").statusCode()).isEqualTo(403);
            assertThat(activities.findLatestLease(first.id(), member).orElseThrow().revokedAt()).isNull();

            // 旧版本遗留的活动/租约即使仍有效，也不能绕过已失效的群籍。
            sql.update("UPDATE groups SET status = 'DISSOLVED', dissolved_at = now() WHERE id = ?", group.id());
            assertThatThrownBy(() -> context.getBean(NetworkAccessService.class).validate(network.networkTicket())).isInstanceOf(ApiException.class).extracting("code").isEqualTo("NETWORK_GRANT_INVALID");
            assertThatThrownBy(() -> context.getBean(VoiceAccessValidator.class).validate(voice.voiceToken())).isInstanceOf(ApiException.class).extracting("code").isEqualTo("VOICE_ACCESS_DENIED");
            assertThat(send(http, entry, "POST", "/activities/" + first.id() + "/network-grants", tokens.get(2), json.writeValueAsString(Map.of("publicKey", publicKey))).statusCode()).isEqualTo(404);
            sql.update("UPDATE groups SET status = 'ACTIVE', dissolved_at = null WHERE id = ?", group.id());

            // 清理和记录均在同一解散事务，审计失败不能留下半解散状态。
            int beforeEvents = sql.queryForObject("SELECT count(*) FROM outbox_events WHERE group_id = ?", Integer.class, group.id());
            sql.execute("CREATE FUNCTION reject_test_dissolution() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action = 'GROUP_DISSOLVE' THEN RAISE EXCEPTION 'test dissolution audit rejected'; END IF; RETURN NEW; END $$");
            sql.execute("CREATE TRIGGER reject_test_dissolution BEFORE INSERT ON audit_logs FOR EACH ROW EXECUTE FUNCTION reject_test_dissolution()");
            assertThatThrownBy(() -> groups.dissolveGroup(owner, group.id())).isInstanceOf(RuntimeException.class);
            assertThat(groups.getGroup(owner, group.id()).status()).isEqualTo(GroupStatus.ACTIVE);
            assertThat(roomRepository.findActivity(first.id()).orElseThrow().state()).isEqualTo(ActivityState.ACTIVE);
            assertThat(activities.findMember(first.id(), member).orElseThrow().state()).isEqualTo(MemberState.GRACE);
            assertThat(activities.findLatestLease(first.id(), member).orElseThrow().revokedAt()).isNull();
            context.getBean(NetworkAccessService.class).validate(network.networkTicket());
            context.getBean(VoiceAccessValidator.class).validate(voice.voiceToken());
            assertThat(sql.queryForObject("SELECT count(*) FROM outbox_events WHERE group_id = ?", Integer.class, group.id())).isEqualTo(beforeEvents);
            sql.execute("DROP TRIGGER reject_test_dissolution ON audit_logs");
            sql.execute("DROP FUNCTION reject_test_dissolution()");
            assertThat(send(http, entry, "POST", dissolvePath, tokens.get(0), "{}").statusCode()).isEqualTo(200);

            for (Activity activity : List.of(first, second)) {
                assertThat(roomRepository.findActivity(activity.id()).orElseThrow().state()).isEqualTo(ActivityState.ENDED);
                assertThat(roomRepository.findActivity(activity.id()).orElseThrow().activityGeneration()).isEqualTo(activity.activityGeneration() + 1);
                for (ActivityMember current : activities.listMembers(activity.id())) {
                    assertThat(current.state()).isEqualTo(MemberState.REVOKED);
                    assertThat(activities.findLatestLease(activity.id(), current.userId()).orElseThrow().revokedAt()).isNotNull();
                    assertThat(activities.findAddress(activity.id(), current.userId()).orElseThrow().releasedAt()).isNotNull();
                    assertThat(activities.findAccountActivity(current.userId())).isEmpty();
                }
            }
            for (Room room : List.of(firstRoom, secondRoom)) {
                Room current = roomRepository.findRoom(room.id()).orElseThrow();
                assertThat(current.currentActivityId()).isNull();
                assertThat(current.openState()).isEqualTo(RoomOpenState.PAUSED);
            }
            assertThat(context.getBean(GrantRepository.class).findById(network.grantId()).orElseThrow().revokedAt()).isNotNull();
            assertThat(context.getBean(GrantRepository.class).findById(voice.grantId()).orElseThrow().revokedAt()).isNotNull();
            assertThatThrownBy(() -> context.getBean(NetworkAccessService.class).validate(network.networkTicket())).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> context.getBean(VoiceAccessValidator.class).validate(voice.voiceToken())).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> context.getBean(RelayGrantValidationService.class).validate(network.networkTicket())).isInstanceOf(ApiException.class);
            assertThat(send(http, entry, "POST", "/activities/" + first.id() + "/voice-grants", tokens.get(2), "{}").statusCode()).isIn(403, 404);
            assertThat(send(http, entry, "POST", "/activities/" + first.id() + "/network-grants", tokens.get(2), json.writeValueAsString(Map.of("publicKey", publicKey))).statusCode()).isIn(403, 404);
            assertThat(send(http, entry, "POST", "/activities/" + first.id() + "/resume", tokens.get(2), "{\"deviceId\":\"member-device\"}").statusCode()).isIn(404, 409);
            String heartbeat = json.writeValueAsString(Map.of("deviceId", ownerJoin.deviceId(), "leaseId", ownerJoin.leaseId(), "activityGeneration", ownerJoin.activityGeneration(), "memberGeneration", ownerJoin.memberGeneration()));
            assertThat(send(http, entry, "POST", "/activities/" + first.id() + "/heartbeat", tokens.get(0), heartbeat).statusCode()).isIn(404, 409);
            for (String path : List.of("/groups/" + group.id(), "/groups/" + group.id() + "/messages", "/groups/" + group.id() + "/audit-logs", "/rooms/" + firstRoom.id(), "/activities/" + first.id() + "/members")) {
                assertThat(send(http, entry, "GET", path, tokens.get(0), null).statusCode()).isEqualTo(404);
            }
            assertThat(send(http, entry, "POST", "/invitations/" + pending.id() + "/accept", tokens.get(4), "{}").statusCode()).isEqualTo(404);
            assertThat(send(http, entry, "POST", "/owner-transfers/" + transfer.id() + "/accept", tokens.get(1), "{}").statusCode()).isEqualTo(404);
            assertThat(context.getBean(SyncService.class).history(member, 0)).noneMatch(event -> group.id().equals(event.groupId()));
            assertThat(sql.queryForObject("SELECT count(*) FROM outbox_events WHERE group_id = ? AND type IN ('voice.revoked','network.revoked','lease.revoked')", Integer.class, group.id())).isEqualTo(9);
            context.getBean(NetworkAccessService.class).validate(unaffectedNetwork.networkTicket());
            assertThat(activities.findMember(unaffectedActivity.id(), outsider).orElseThrow().state()).isEqualTo(MemberState.ACTIVE);

            try (var executor = Executors.newFixedThreadPool(2)) {
                for (int round = 0; round < 3; round++) {
                    Group race = groups.createGroup(owner, "并发解散-" + round, null, "");
                    groups.acceptInvitation(member, groups.invite(owner, race.id(), member).id());
                    Room room = rooms.createRoom(owner, race.id(), "并发房", "", "", null);
                    Activity activity = rooms.startActivity(owner, room.id());
                    AuthenticatedActivities.join(context, member, room.id(), "before-device", null, "before-join");
                    var start = new CountDownLatch(1);
                    var dissolving = executor.submit(() -> { start.await(); groups.dissolveGroup(owner, race.id()); return true; });
                    var joining = executor.submit(() -> { start.await(); return send(http, entry, "POST", "/room-joins", tokens.get(2), json.writeValueAsString(Map.of("roomId", room.id(), "deviceId", "member-device", "operationId", "after-join"))).statusCode(); });
                    start.countDown();
                    assertThat(dissolving.get(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(joining.get(10, TimeUnit.SECONDS)).isIn(200, 404);
                    assertThat(activities.findMember(activity.id(), member).orElseThrow().state()).isEqualTo(MemberState.REVOKED);
                    assertThat(activities.findAccountActivity(member)).isEmpty();
                }
            }
        }
    }

    private static HttpResponse<String> send(HttpClient http, String entry, String method, String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(entry + "/api/v1" + path)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
