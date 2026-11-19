package app.weavora.zhijie.controlplane.group;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.*;
import app.weavora.zhijie.controlplane.grant.*;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.room.*;
import app.weavora.zhijie.controlplane.sync.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

class GroupGovernanceIntegrationTest {
    @Test
    void httpRoleMatrixRejectsStalePrivilegesAndRemovalRevokesActivityAndTransfersRooms() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identities = context.getBean(IdentityRepository.class);
            var identity = context.getBean(IdentityService.class);
            var groups = context.getBean(GroupService.class);
            var repository = context.getBean(GroupRepository.class);
            String hash = context.getBean(CredentialProtector.class).hashPassword("integration-password-123");
            var users = new ArrayList<String>();
            var tokens = new ArrayList<String>();
            for (int index = 0; index < 6; index++) {
                String id = UUID.randomUUID().toString(), email = "governance-" + index + "@example.test";
                identities.insertUser(new UserAccount(id, email, AccountStatus.ACTIVE, hash, Instant.now()));
                users.add(id);
                tokens.add(identity.login(email, "integration-password-123", "governance-device").accessToken());
            }
            String owner = users.get(0), admin = users.get(1), peerAdmin = users.get(2), member = users.get(3), removed = users.get(4), outsider = users.get(5);
            Group group = groups.createGroup(owner, "治理验证", null, "");
            for (String user : List.of(admin, peerAdmin, member, removed)) {
                groups.acceptInvitation(user, groups.invite(owner, group.id(), user).id());
            }
            groups.changeRole(owner, group.id(), admin, GroupRole.ADMIN);
            groups.changeRole(owner, group.id(), peerAdmin, GroupRole.ADMIN);
            String entry = "http://127.0.0.1:" + ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var http = HttpClient.newHttpClient();
            String base = "/groups/" + group.id(), target = base + "/members/";
            assertThat(send(http, entry, "GET", base + "/members", tokens.get(5), null).statusCode()).isEqualTo(404);
            assertThat(send(http, entry, "PATCH", target + member, tokens.get(3), "{\"role\":\"ADMIN\"}").statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "PATCH", target + member, tokens.get(1), "{\"role\":\"ADMIN\"}").statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "PATCH", target + member, tokens.get(0), "{\"role\":\"OWNER\"}").statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "PATCH", target + owner, tokens.get(0), "{\"role\":\"ADMIN\"}").statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "DELETE", target + owner, tokens.get(1), null).statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "DELETE", target + peerAdmin, tokens.get(1), null).statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "DELETE", target + admin, tokens.get(1), null).statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "DELETE", target + removed, tokens.get(3), null).statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "POST", base + "/leave", tokens.get(0), "{}").statusCode()).isEqualTo(409);
            assertThat(send(http, entry, "PATCH", target + member, tokens.get(0), "{\"role\":\"ADMIN\"}").statusCode()).isEqualTo(200);
            assertThat(repository.findMember(group.id(), member).orElseThrow().role()).isEqualTo(GroupRole.ADMIN);
            assertThat(send(http, entry, "PATCH", target + member, tokens.get(0), "{\"role\":\"MEMBER\"}").statusCode()).isEqualTo(200);
            assertThat(repository.findMember(group.id(), member).orElseThrow().role()).isEqualTo(GroupRole.MEMBER);

            // 同一个旧登录令牌在降权后立刻失去管理权限。
            assertThat(send(http, entry, "PATCH", target + admin, tokens.get(0), "{\"role\":\"MEMBER\"}").statusCode()).isEqualTo(200);
            assertThat(send(http, entry, "DELETE", target + removed, tokens.get(1), null).statusCode()).isEqualTo(403);
            assertThat(send(http, entry, "PATCH", base, tokens.get(1), "{\"name\":\"旧页面修改\"}").statusCode()).isEqualTo(403);

            var rooms = context.getBean(RoomService.class);
            Room room = rooms.createRoom(owner, group.id(), "待接管房间", "", "", null);
            rooms.assignOwner(owner, room.id(), removed);
            Activity activity = rooms.startActivity(removed, room.id());
            var activities = context.getBean(ActivityRepository.class);
            context.getBean(ActivityCoordinator.class).join(removed, room.id(), "removed-device", null, "join-governance");
            byte[] key = new byte[32];
            for (int index = 0; index < key.length; index++) key[index] = (byte)(index + 1);
            var grant = context.getBean(GrantService.class).network(removed, activity.id(), Base64.getEncoder().encodeToString(key));
            var invitation = groups.invite(removed, group.id(), outsider);
            var outbox = context.getBean(OutboxRepository.class);
            long removalCursor = outbox.latestSequence();
            assertThat(send(http, entry, "DELETE", target + removed, tokens.get(2), null).statusCode()).isEqualTo(200);
            var departed = repository.findMember(group.id(), removed).orElseThrow();
            assertThat(departed.status()).isEqualTo(MemberStatus.REMOVED);
            assertThat(departed.role()).isEqualTo(GroupRole.MEMBER);
            assertThat(departed.leftAt()).isNotNull();
            assertThat(context.getBean(RoomRepository.class).findRoom(room.id()).orElseThrow().ownerUserId()).isEqualTo(owner);
            assertThat(activities.findMember(activity.id(), removed).orElseThrow().state()).isEqualTo(MemberState.REVOKED);
            assertThat(activities.findLatestLease(activity.id(), removed).orElseThrow().revokedAt()).isNotNull();
            assertThat(activities.findAccountActivity(removed)).isEmpty();
            assertThat(activities.findAddress(activity.id(), removed).orElseThrow().releasedAt()).isNotNull();
            assertThat(context.getBean(GrantRepository.class).findById(grant.grantId()).orElseThrow().revokedAt()).isNotNull();
            assertThat(repository.findInvitation(invitation.id()).orElseThrow().status()).isEqualTo(InvitationStatus.INVALIDATED);
            assertThat(outbox.listAfter(removalCursor, 20)).extracting("type")
                    .contains("voice.revoked", "network.revoked", "lease.revoked");
            assertThat(send(http, entry, "GET", base + "/members", tokens.get(4), null).statusCode()).isEqualTo(404);
            assertThat(send(http, entry, "POST", "/invitations/" + invitation.id() + "/accept", tokens.get(5), "{}").statusCode()).isEqualTo(404);
            var page = context.getBean(ObjectMapper.class).readTree(send(http, entry, "GET", base + "/members", tokens.get(0), null).body()).path("data");
            assertThat(page.toString()).doesNotContain(removed);
            assertThat(send(http, entry, "DELETE", target + peerAdmin, tokens.get(0), null).statusCode()).isEqualTo(200);
        }
    }

    private static HttpResponse<String> send(HttpClient http, String entry, String method, String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(entry + "/api/v1" + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
