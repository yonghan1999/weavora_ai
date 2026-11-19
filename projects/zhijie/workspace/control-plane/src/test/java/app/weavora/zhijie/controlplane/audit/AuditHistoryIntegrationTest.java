package app.weavora.zhijie.controlplane.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.group.*;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.message.MessageService;
import app.weavora.zhijie.controlplane.room.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

class AuditHistoryIntegrationTest {
    @Test
    void realOperationsAppendSecretFreeHistoryWithStablePrivateReadOnlyPagination() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identities = context.getBean(IdentityRepository.class);
            var identity = context.getBean(IdentityService.class);
            var groups = context.getBean(GroupService.class);
            var rooms = context.getBean(RoomService.class);
            var sql = context.getBean(JdbcTemplate.class);
            var json = context.getBean(ObjectMapper.class);
            String password = "audit-secret-password-123";
            String hash = context.getBean(CredentialProtector.class).hashPassword(password);
            var users = new ArrayList<String>(); var tokens = new ArrayList<String>();
            for (int index = 0; index < 5; index++) {
                String id = UUID.randomUUID().toString(), email = "audit-" + index + "@example.test";
                identities.insertUser(new UserAccount(id, email, AccountStatus.ACTIVE, hash, Instant.now()));
                users.add(id); tokens.add(identity.login(email, password, "audit-device").accessToken());
            }
            String owner = users.get(0), admin = users.get(1), member = users.get(2), outsider = users.get(3), invitee = users.get(4);
            Group group = groups.createGroup(owner, "记录群", null, "");
            for (String user : List.of(admin, member)) groups.acceptInvitation(user, groups.invite(owner, group.id(), user).id());
            groups.changeRole(owner, group.id(), admin, GroupRole.ADMIN);
            groups.updateGroup(admin, group.id(), "修改后的记录群", null, "private-description-secret");
            GroupInvitation revoked = groups.invite(owner, group.id(), invitee);
            groups.revokeInvitation(admin, group.id(), revoked.id());
            OwnerTransfer transfer = groups.initiateTransfer(owner, group.id(), member);
            groups.cancelTransfer(owner, transfer.id());
            groups.requestRecovery(member, group.id(), "private-recovery-evidence-secret");
            Room room = rooms.createRoom(admin, group.id(), "记录房间", "", "", "room-secret-password-123");
            rooms.updateRoom(admin, room.id(), "修改房间", "private-room-description-secret", "", "new-room-secret-password-123");
            rooms.setOpenState(admin, room.id(), RoomOpenState.PAUSED);
            rooms.setOpenState(admin, room.id(), RoomOpenState.OPEN);
            rooms.assignOwner(owner, room.id(), member);
            Activity activity = rooms.startActivity(admin, room.id());
            rooms.endActivity(admin, activity.id());
            rooms.deleteRoom(admin, room.id());
            var messages = context.getBean(MessageService.class);
            var posted = messages.post(member, group.id(), "private-message-secret");
            messages.delete(admin, posted.id(), "audit-message-correlation");
            groups.leaveGroup(member, group.id());
            groups.acceptInvitation(member, groups.invite(owner, group.id(), member).id());
            groups.removeMember(admin, group.id(), member);
            var actions = sql.queryForList("SELECT action FROM audit_logs WHERE group_id = ?", String.class, group.id());
            assertThat(actions).contains("GROUP_CREATE", "GROUP_UPDATE", "GROUP_INVITE", "GROUP_INVITATION_ACCEPT",
                    "GROUP_INVITATION_REVOKE", "GROUP_ADMIN_GRANT", "OWNER_TRANSFER_CREATE", "OWNER_TRANSFER_CANCEL",
                    "OWNER_RECOVERY_REQUEST", "ROOM_CREATE", "ROOM_UPDATE", "ROOM_PAUSE", "ROOM_OPEN", "ROOM_OWNER_ASSIGN",
                    "ACTIVITY_START", "ACTIVITY_END", "ROOM_DELETE", "MESSAGE_DELETE", "GROUP_LEAVE", "GROUP_MEMBER_REMOVE");
            String stored = sql.queryForList("SELECT * FROM audit_logs WHERE group_id = ?", group.id()).toString();
            assertThat(stored).doesNotContain(password, hash, "room-secret-password-123", "new-room-secret-password-123",
                    "private-description-secret", "private-room-description-secret", "private-message-secret", "private-recovery-evidence-secret");
            for (String token : tokens) assertThat(stored).doesNotContain(token);

            Group foreign = groups.createGroup(outsider, "其他群", null, "");
            String entry = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var http = HttpClient.newHttpClient();
            String path = "/groups/" + group.id() + "/audit-logs";
            assertThat(send(http, entry, "GET", path, tokens.get(2)).statusCode()).isEqualTo(404);
            assertThat(send(http, entry, "GET", path, tokens.get(3)).statusCode()).isEqualTo(404);
            assertThat(send(http, entry, "GET", path, tokens.get(1)).statusCode()).isEqualTo(200);
            for (String method : List.of("POST", "PATCH", "PUT", "DELETE")) {
                assertThat(send(http, entry, method, path, tokens.get(0)).statusCode()).isIn(404, 405);
            }
            assertThat(sql.queryForObject("SELECT count(*) FROM audit_logs WHERE group_id = ?", Integer.class, group.id())).isEqualTo(actions.size());

            var fixed = new AuditRecorder(context.getBean(AuditRepository.class), Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));
            for (int index = 0; index < 53; index++) fixed.record(group.id(), owner, "GROUP", group.id(), "GROUP_UPDATE", "SUCCESS", "page-" + index);
            var first = json.readTree(send(http, entry, "GET", path, tokens.get(0)).body()).path("data");
            assertThat(first.size()).isEqualTo(50);
            var seen = new LinkedHashSet<String>();
            first.forEach(row -> {
                seen.add(row.path("id").asText());
                for (String field : List.of("actorId", "targetType", "targetId", "occurredAt", "action", "result")) {
                    assertThat(row.path(field).asText()).isNotBlank();
                }
            });
            String cursor = first.get(49).path("id").asText();
            var next = json.readTree(send(http, entry, "GET", path + "?beforeId=" + cursor, tokens.get(0)).body()).path("data");
            next.forEach(row -> assertThat(seen.add(row.path("id").asText())).isTrue());
            assertThat(seen).hasSize(actions.size() + 53);
            String foreignCursor = sql.queryForObject("SELECT id FROM audit_logs WHERE group_id = ? LIMIT 1", String.class, foreign.id());
            assertThat(json.readTree(send(http, entry, "GET", path + "?beforeId=" + foreignCursor, tokens.get(0)).body()).path("data")).isEmpty();
            assertThat(send(http, entry, "GET", path + "?beforeId=", tokens.get(0)).statusCode()).isEqualTo(422);
            assertThat(send(http, entry, "GET", path + "?beforeId=" + "x".repeat(129), tokens.get(0)).statusCode()).isEqualTo(422);
            assertThat(json.readTree(send(http, entry, "GET", path + "?limit=1000", tokens.get(0)).body()).path("data").size()).isLessThanOrEqualTo(100);

            // 记录写入失败时，业务变更也必须回滚，不能出现无记录的管理成功。
            sql.execute("CREATE FUNCTION reject_test_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action = 'GROUP_UPDATE' THEN RAISE EXCEPTION 'test audit insert rejected'; END IF; RETURN NEW; END $$");
            sql.execute("CREATE TRIGGER reject_test_audit BEFORE INSERT ON audit_logs FOR EACH ROW EXECUTE FUNCTION reject_test_audit()");
            assertThatThrownBy(() -> groups.updateGroup(owner, group.id(), "不应保存", null, "")).isInstanceOf(RuntimeException.class);
            assertThat(groups.getGroup(owner, group.id()).name()).isEqualTo("修改后的记录群");
            sql.execute("DROP TRIGGER reject_test_audit ON audit_logs");
            sql.execute("DROP FUNCTION reject_test_audit()");

            groups.changeRole(owner, group.id(), admin, GroupRole.MEMBER);
            assertThat(send(http, entry, "GET", path, tokens.get(1)).statusCode()).isEqualTo(403);
            OwnerTransfer accepted = groups.initiateTransfer(owner, group.id(), admin);
            groups.acceptTransfer(admin, accepted.id());
            assertThat(send(http, entry, "GET", path, tokens.get(0)).statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "GET", path, tokens.get(1)).statusCode()).isEqualTo(200);
            groups.dissolveGroup(admin, group.id());
            assertThat(send(http, entry, "GET", path, tokens.get(1)).statusCode()).isEqualTo(404);
            assertThat(sql.queryForList("SELECT action FROM audit_logs WHERE group_id = ?", String.class, group.id()))
                    .contains("GROUP_ADMIN_REVOKE", "OWNER_TRANSFER_ACCEPT", "GROUP_DISSOLVE");
        }
    }

    private static HttpResponse<String> send(HttpClient http, String entry, String method, String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(entry + "/api/v1" + path)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
