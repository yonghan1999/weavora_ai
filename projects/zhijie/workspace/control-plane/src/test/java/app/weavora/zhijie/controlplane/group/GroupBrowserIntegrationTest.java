package app.weavora.zhijie.controlplane.group;

import static org.assertj.core.api.Assertions.assertThat;
import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.message.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class GroupBrowserIntegrationTest {
    private final Path workspace = Path.of(System.getProperty("user.dir")).getParent();
    private final Path ui = workspace.resolve("desktop/ui");

    private Path clientExecutable;
    private Path pageAssets = ui.resolve("dist");

    private void prepareClients() throws Exception {
        String extracted = System.getenv("ZHIJIE_TEST_EXTRACTED_UI");
        if (extracted == null) {
            var build = new ProcessBuilder("node", "scripts/build.mjs").directory(ui.toFile()).inheritIO().start();
            try {
                assertThat(build.waitFor(30, TimeUnit.SECONDS)).isTrue();
                assertThat(build.exitValue()).isZero();
            } finally { build.destroyForcibly(); }
        } else {
            pageAssets = Path.of(extracted).toRealPath();
            assertThat(pageAssets.startsWith(workspace.resolve("target").toRealPath())).isTrue();
            assertThat(pageAssets.getFileName().toString()).isEqualTo("dist");
            assertThat(pageAssets.resolve("index.html")).isRegularFile();
            assertThat(pageAssets.resolve("app.js")).isRegularFile();
        }
        var client = new ProcessBuilder("node", "tests/prepare-group-client.mjs").directory(ui.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            assertThat(client.waitFor(300, TimeUnit.SECONDS)).as("Shell test client build deadline").isTrue();
            assertThat(client.exitValue()).isZero();
            clientExecutable = Path.of(new String(client.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertThat(clientExecutable).isAbsolute().isRegularFile();
        } finally {
            client.descendants().forEach(ProcessHandle::destroyForcibly);
            client.destroyForcibly();
        }
    }

    private ServletWebServerApplicationContext start(EmbeddedPostgres postgres) {
        return (ServletWebServerApplicationContext) new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0",
                "--zhijie.voice.enabled=false",
                "--spring.web.resources.static-locations=" + pageAssets.toUri(),
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=");
    }

    private void runBrowser(ServletWebServerApplicationContext context, String password, String scenario) throws Exception {
        runBrowser(context, password, scenario, null);
    }

    private void runBrowser(ServletWebServerApplicationContext context, String password, String scenario, Runnable onReady) throws Exception {
        var profile = Files.createTempDirectory(workspace.resolve("target"), "group-browser-" + scenario + "-");
        var browser = new ProcessBuilder("node", "tests/group-browser.mjs", profile.toString(), clientExecutable.toString())
                .directory(ui.toFile()).redirectErrorStream(true).redirectOutput(profile.resolve("scenario.log").toFile()).start();
        try {
            try (var input = browser.getOutputStream()) {
                context.getBean(ObjectMapper.class).writeValue(input, Map.of("port", context.getWebServer().getPort(), "password", password, "scenario", scenario));
            }
            if (onReady != null) {
                var ready = profile.resolve("ready");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!Files.exists(ready) && browser.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
                assertThat(ready).as("browser logged in: %s", profile).isRegularFile();
                onReady.run();
                Files.createFile(profile.resolve("checked"));
            }
            assertThat(browser.waitFor(250, TimeUnit.SECONDS)).as("browser deadline: %s", profile).isTrue();
            assertThat(browser.exitValue()).as("browser steps: %s", profile.resolve("scenario.log")).isZero();
        } finally {
            browser.descendants().forEach(ProcessHandle::destroyForcibly);
            browser.destroyForcibly();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dissolved-chat", "dissolved-dialog"})
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_GROUP_BROWSER", matches = "true")
    void dissolvingViewedGroupKeepsOtherGroupsActiveRoom(String scenario) throws Exception {
        prepareClients();
        try (var postgres = EmbeddedPostgres.start(); var context = start(postgres)) {
            var identities = context.getBean(IdentityRepository.class);
            String password = UUID.randomUUID().toString();
            String hash = context.getBean(CredentialProtector.class).hashPassword(password);
            String owner = UUID.randomUUID().toString(), member = UUID.randomUUID().toString();
            identities.insertUser(new UserAccount(owner, "owner@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            identities.insertUser(new UserAccount(member, "member@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            var groups = context.getBean(GroupService.class);
            var retained = groups.createGroup(member, "保留群", null, "");
            var dissolved = groups.createGroup(owner, "待解散群", null, "");
            groups.acceptInvitation(member, groups.invite(owner, dissolved.id(), member).id());
            var rooms = context.getBean(app.weavora.zhijie.controlplane.room.RoomService.class);
            var activity = rooms.startActivity(member, rooms.createRoom(member, retained.id(), "保留房间", "", "", null).id());
            runBrowser(context, password, scenario);
            assertThat(context.getBean(GroupRepository.class).findGroup(dissolved.id()).orElseThrow().status()).isEqualTo(GroupStatus.DISSOLVED);
            var repository = context.getBean(ActivityRepository.class);
            assertThat(repository.findAccountActivity(member).orElseThrow().activityId()).isEqualTo(activity.id());
            assertThat(repository.countOccupied(activity.id())).isOne();
            var lease = repository.findLatestLease(activity.id(), member).orElseThrow();
            assertThat(lease.revokedAt()).isNull();
            context.getBean(ActivityCoordinator.class).heartbeat(member, activity.id(), lease.deviceId(), lease.id(), lease.activityGeneration(), lease.memberGeneration());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_GROUP_BROWSER", matches = "true")
    void secondDeviceTakesOverTheRoomButBothDevicesKeepGroupAccess() throws Exception {
        prepareClients();
        try (var postgres = EmbeddedPostgres.start(); var context = start(postgres)) {
            String password = UUID.randomUUID().toString(), user = UUID.randomUUID().toString();
            String hash = context.getBean(CredentialProtector.class).hashPassword(password);
            context.getBean(IdentityRepository.class).insertUser(new UserAccount(user, "owner@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            var group = context.getBean(GroupService.class).createGroup(user, "双端接管群", null, "");
            var rooms = context.getBean(app.weavora.zhijie.controlplane.room.RoomService.class);
            var room = rooms.createRoom(user, group.id(), "设备接管房", "", "", null);
            var activity = rooms.startActivity(user, room.id());
            runBrowser(context, password, "device-takeover");
            var repository = context.getBean(ActivityRepository.class);
            var lease = repository.findLatestLease(activity.id(), user).orElseThrow();
            var pointer = repository.findAccountActivity(user).orElseThrow();
            assertThat(pointer.deviceId()).isEqualTo(lease.deviceId());
            assertThat(pointer.memberGeneration()).isEqualTo(lease.memberGeneration()).isEqualTo(2L);
            assertThat(repository.countOccupied(activity.id())).isOne();
            var sql = context.getBean(JdbcTemplate.class);
            assertThat(sql.queryForList("SELECT revoked_at IS NOT NULL FROM connection_leases WHERE activity_id=? AND user_id=? ORDER BY member_generation", Boolean.class, activity.id(), user)).containsExactly(true, false);
            assertThat(sql.queryForObject("SELECT count(DISTINCT device_id) FROM auth_sessions WHERE user_id=? AND revoked_at IS NULL", Integer.class, user)).isEqualTo(2);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_GROUP_BROWSER", matches = "true")
    void memberRetriesProtectedRoomWithoutLosingOriginalLease() throws Exception {
        prepareClients();
        try (var postgres = EmbeddedPostgres.start(); var context = start(postgres)) {
            var identities = context.getBean(IdentityRepository.class);
            String password = UUID.randomUUID().toString();
            String hash = context.getBean(CredentialProtector.class).hashPassword(password);
            String owner = UUID.randomUUID().toString(), member = UUID.randomUUID().toString();
            identities.insertUser(new UserAccount(owner, "owner@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            identities.insertUser(new UserAccount(member, "member@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            var groups = context.getBean(GroupService.class);
            var group = groups.createGroup(owner, "切房验证群", null, "");
            groups.acceptInvitation(member, groups.invite(owner, group.id(), member).id());
            var rooms = context.getBean(app.weavora.zhijie.controlplane.room.RoomService.class);
            var original = rooms.startActivity(owner, rooms.createRoom(owner, group.id(), "原房间", "", "", null).id());
            var target = rooms.startActivity(owner, rooms.createRoom(owner, group.id(), "密码房", "", "", "room-" + password).id());
            var repository = context.getBean(ActivityRepository.class);
            var coordinator = context.getBean(ActivityCoordinator.class);
            var identity = context.getBean(IdentityService.class);
            for (int seat = 0; seat < 11; seat++) {
                String user = UUID.randomUUID().toString(), email = "seat-" + seat + "@example.test";
                identities.insertUser(new UserAccount(user, email, AccountStatus.ACTIVE, hash, Instant.now()));
                groups.acceptInvitation(user, groups.invite(owner, group.id(), user).id());
                var tokens = identity.login(email, password, "seat-" + seat);
                coordinator.join(identity.authenticateSession("Bearer " + tokens.accessToken()), target.roomId(), "room-" + password, UUID.randomUUID().toString());
            }
            runBrowser(context, password, "protected-switch", () -> {
                assertThat(repository.countOccupied(target.id())).isEqualTo(12);
                assertThat(repository.findAccountActivity(member).orElseThrow().activityId()).isEqualTo(original.id());
                assertThat(repository.findLatestLease(target.id(), member)).isEmpty();
                var lease = repository.findLatestLease(original.id(), member).orElseThrow();
                coordinator.heartbeat(member, original.id(), lease.deviceId(), lease.id(), lease.activityGeneration(), lease.memberGeneration());
            });
            assertThat(repository.countOccupied(target.id())).isEqualTo(12);
            assertThat(repository.findLatestLease(target.id(), owner).orElseThrow().revokedAt()).isNotNull();
            assertThat(repository.findAccountActivity(member).orElseThrow().activityId()).isEqualTo(target.id());
            assertThat(repository.findLatestLease(original.id(), member).orElseThrow().revokedAt()).isNotNull();
            assertThat(repository.findLatestLease(target.id(), member).orElseThrow().revokedAt()).isNull();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_GROUP_BROWSER", matches = "true")
    void revokedDesktopSessionExplainsLossAndCanLoginAgain() throws Exception {
        prepareClients();
        try (var postgres = EmbeddedPostgres.start(); var context = start(postgres)) {
            var identities = context.getBean(IdentityRepository.class);
            String password = UUID.randomUUID().toString(), user = UUID.randomUUID().toString();
            String hash = context.getBean(CredentialProtector.class).hashPassword(password);
            identities.insertUser(new UserAccount(user, "owner@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            context.getBean(GroupService.class).createGroup(user, "会话恢复验证群", null, "");
            var sql = context.getBean(JdbcTemplate.class);
            runBrowser(context, password, "session-revoked", () -> {
                assertThat(sql.queryForObject("SELECT count(*) FROM auth_sessions WHERE user_id=? AND revoked_at IS NULL", Integer.class, user)).isOne();
                identities.revokeAllSessions(user, Instant.now());
            });
            assertThat(sql.queryForList("SELECT revoked_at IS NOT NULL FROM auth_sessions WHERE user_id=?", Boolean.class, user))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_GROUP_BROWSER", matches = "true")
    void assignedRoomOwnerKeepsRoomOnLeaveButGroupOwnerTakesOverOnRemoval() throws Exception {
        prepareClients();
        try (var postgres = EmbeddedPostgres.start(); var context = start(postgres)) {
            var identities = context.getBean(IdentityRepository.class);
            String password = UUID.randomUUID().toString();
            String hash = context.getBean(CredentialProtector.class).hashPassword(password);
            String owner = UUID.randomUUID().toString(), member = UUID.randomUUID().toString();
            identities.insertUser(new UserAccount(owner, "owner@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            identities.insertUser(new UserAccount(member, "member@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            var groups = context.getBean(GroupService.class);
            var group = groups.createGroup(owner, "房主接管验证群", null, "");
            groups.acceptInvitation(member, groups.invite(owner, group.id(), member).id());
            var rooms = context.getBean(app.weavora.zhijie.controlplane.room.RoomService.class);
            var room = rooms.createRoom(owner, group.id(), "房主接管验证房", "", "", null);
            var activity = rooms.startActivity(owner, room.id());
            runBrowser(context, password, "room-ownership");
            var saved = rooms.getRoom(owner, room.id());
            assertThat(saved.ownerUserId()).isEqualTo(owner);
            assertThat(saved.currentActivityId()).isEqualTo(activity.id());
            assertThat(saved.description()).isEqualTo("新房主已保存");
            var sql = context.getBean(JdbcTemplate.class);
            assertThat(sql.queryForObject("SELECT count(*) FROM audit_logs WHERE group_id=? AND target_id=? AND actor_id=? AND action='ROOM_OWNER_ASSIGN'", Integer.class, group.id(), room.id(), owner)).isOne();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_GROUP_BROWSER", matches = "true")
    void roomMembershipSurvivesChannelFailuresAndKickInvalidatesOnlyTheOldLease() throws Exception {
        prepareClients();
        try (var postgres = EmbeddedPostgres.start(); var context = start(postgres)) {
            var identities = context.getBean(IdentityRepository.class);
            String password = UUID.randomUUID().toString();
            String hash = context.getBean(CredentialProtector.class).hashPassword(password);
            String owner = UUID.randomUUID().toString(), member = UUID.randomUUID().toString();
            identities.insertUser(new UserAccount(owner, "owner@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            identities.insertUser(new UserAccount(member, "member@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            var groups = context.getBean(GroupService.class);
            var group = groups.createGroup(owner, "双端房间验证", null, "");
            groups.acceptInvitation(member, groups.invite(owner, group.id(), member).id());
            var rooms = context.getBean(app.weavora.zhijie.controlplane.room.RoomService.class);
            var room = rooms.createRoom(owner, group.id(), "通道故障试用房", "", "", null);
            var activity = rooms.startActivity(owner, room.id());
            runBrowser(context, password, "room-channels");
            var sql = context.getBean(JdbcTemplate.class);
            assertThat(sql.queryForList("SELECT member_generation FROM connection_leases WHERE activity_id=? AND user_id=? ORDER BY member_generation", Long.class, activity.id(), member)).containsExactly(1L, 2L);
            assertThat(sql.queryForList("SELECT revoked_at IS NOT NULL FROM connection_leases WHERE activity_id=? AND user_id=?", Boolean.class, activity.id(), owner)).containsExactly(true);
            var reopened = rooms.getRoom(owner, room.id());
            assertThat(reopened.openState()).isEqualTo(app.weavora.zhijie.controlplane.room.RoomOpenState.OPEN);
            assertThat(reopened.currentActivityId()).isNotNull().isNotEqualTo(activity.id());
            assertThat(sql.queryForList("SELECT user_id FROM connection_leases WHERE activity_id=? AND revoked_at IS NULL", String.class, reopened.currentActivityId())).containsExactly(member);
            assertThat(sql.queryForObject("SELECT count(*) FROM activity_grants WHERE activity_id=? AND user_id=? AND kind='NETWORK' AND member_generation=1 AND revoked_at IS NOT NULL", Integer.class, activity.id(), member)).isGreaterThan(0);
            assertThat(sql.queryForObject("SELECT count(*) FROM connection_leases WHERE activity_id=? AND user_id=? AND revoked_at IS NULL", Integer.class, activity.id(), member)).isZero();
            assertThat(sql.queryForObject("SELECT state FROM activity_members WHERE activity_id=? AND user_id=? AND member_generation=2", String.class, activity.id(), member)).isEqualTo("LEFT");
            assertThat(sql.queryForObject("SELECT count(DISTINCT public_key) FROM activity_grants WHERE activity_id=? AND user_id=? AND member_generation=2 AND kind='NETWORK' AND revoked_at IS NOT NULL", Integer.class, activity.id(), member)).isGreaterThanOrEqualTo(2);
            assertThat(sql.queryForObject("SELECT count(*) FROM ip_allocations WHERE activity_id=? AND user_id=? AND released_at IS NULL", Integer.class, activity.id(), member)).isZero();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_GROUP_BROWSER", matches = "true")
    void groupProfileSurvivesServiceRestartAndFreshBrowser() throws Exception {
        prepareClients();
        String password = UUID.randomUUID().toString();
        String owner = UUID.randomUUID().toString();
        try (var postgres = EmbeddedPostgres.start()) {
            String groupId;
            try (var context = start(postgres)) {
                var hash = context.getBean(CredentialProtector.class).hashPassword(password);
                context.getBean(IdentityRepository.class).insertUser(new UserAccount(owner, "owner@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
                runBrowser(context, password, "profile-write");
                var groups = context.getBean(GroupRepository.class).listGroupsForUser(owner);
                assertThat(groups).hasSize(1);
                groupId = groups.getFirst().id();
            }
            try (var restarted = start(postgres)) {
                var group = restarted.getBean(GroupRepository.class).findGroup(groupId).orElseThrow();
                assertThat(group.ownerUserId()).isEqualTo(owner);
                assertThat(group.name()).isEqualTo("重启验证小队");
                assertThat(group.description()).isEqualTo("修改后的群简介");
                runBrowser(restarted, password, "profile-read");
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_GROUP_BROWSER", matches = "true")
    void twoPagesDeleteHistoricalMessagesAsAuthorAndModerator() throws Exception {
        prepareClients();
        try (var postgres = EmbeddedPostgres.start(); var context = start(postgres)) {
            var identities = context.getBean(IdentityRepository.class);
            String password = UUID.randomUUID().toString();
            String hash = context.getBean(CredentialProtector.class).hashPassword(password);
            String owner = UUID.randomUUID().toString(), member = UUID.randomUUID().toString();
            identities.insertUser(new UserAccount(owner, "owner@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            identities.insertUser(new UserAccount(member, "member@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            var groups = context.getBean(GroupService.class);
            String groupId = groups.createGroup(owner, "历史消息试用群", null, "").id();
            groups.acceptInvitation(member, groups.invite(owner, groupId, member).id());
            var messages = context.getBean(MessageService.class);
            var untouched = messages.post(owner, groupId, "群主保留");
            var own = messages.post(member, groupId, "成员自删");
            var moderated = messages.post(member, groupId, "群主代删");
            for (int index = 0; index < 50; index++) messages.post(owner, groupId, "近期消息 " + index);
            // Keep all three history fixtures behind the 50-row cursor even on a coarse clock.
            var sql = context.getBean(JdbcTemplate.class);
            assertThat(sql.update("UPDATE messages SET created_at=created_at - interval '1 day' WHERE id IN (?,?,?)",
                    untouched.id(), own.id(), moderated.id())).isEqualTo(3);
            runBrowser(context, password, "history-delete");
            assertThat(sql.queryForObject("SELECT deleted_by FROM messages WHERE id=? AND delete_kind='AUTHOR'", String.class, own.id())).isEqualTo(member);
            assertThat(sql.queryForObject("SELECT deleted_by FROM messages WHERE id=? AND delete_kind='MODERATOR'", String.class, moderated.id())).isEqualTo(owner);
            assertThat(sql.queryForObject("SELECT count(*) FROM messages WHERE id=? AND deleted_at IS NULL", Integer.class, untouched.id())).isOne();
            assertThat(sql.queryForList("SELECT target_id FROM audit_logs WHERE group_id=? AND actor_id=? AND action='MESSAGE_DELETE'", String.class, groupId, owner)).containsExactly(moderated.id());
            assertThat(sql.queryForList("SELECT aggregate_id FROM outbox_events WHERE type='message.deleted'", String.class)).containsExactlyInAnyOrder(own.id(), moderated.id());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_GROUP_BROWSER", matches = "true")
    void twoPagesRevokeRemoveAndReinviteThroughTheControlPlane() throws Exception {
        prepareClients();
        try (var postgres = EmbeddedPostgres.start(); var context = start(postgres)) {
            var identities = context.getBean(IdentityRepository.class);
            String password = UUID.randomUUID().toString();
            String hash = context.getBean(CredentialProtector.class).hashPassword(password);
            String owner = UUID.randomUUID().toString(), member = UUID.randomUUID().toString();
            identities.insertUser(new UserAccount(owner, "owner@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            identities.insertUser(new UserAccount(member, "member@example.test", AccountStatus.ACTIVE, hash, Instant.now()));
            var group = context.getBean(GroupService.class).createGroup(owner, "双端试用群", null, "");
            runBrowser(context, password, "social");
            var sql = context.getBean(JdbcTemplate.class);
            assertThat(sql.queryForList("SELECT status FROM friend_requests WHERE requester_id=? AND addressee_id=?", String.class, owner, member))
                    .containsExactlyInAnyOrder("REJECTED", "ACCEPTED");
            assertThat(sql.queryForObject("SELECT count(*) FROM friendships", Integer.class)).isZero();
            assertThat(sql.queryForList("SELECT status FROM owner_transfers WHERE group_id=? AND from_user_id=? AND to_user_id=?", String.class, group.id(), owner, member))
                    .containsExactlyInAnyOrder("CANCELLED", "ACCEPTED");
            assertThat(sql.queryForList("SELECT status FROM owner_transfers WHERE group_id=? AND from_user_id=? AND to_user_id=?", String.class, group.id(), member, owner))
                    .containsExactly("ACCEPTED");
            var groups = context.getBean(GroupRepository.class);
            var savedGroup = groups.findGroup(group.id()).orElseThrow();
            assertThat(savedGroup.ownerUserId()).isEqualTo(owner);
            assertThat(savedGroup.name()).isEqualTo("双端新群名");
            assertThat(savedGroup.description()).isEqualTo("同步到已打开群聊的简介");
            assertThat(groups.findMember(group.id(), owner).orElseThrow().role()).isEqualTo(GroupRole.OWNER);
            var persisted = groups.findMember(group.id(), member).orElseThrow();
            assertThat(persisted.status()).isEqualTo(MemberStatus.REMOVED);
            assertThat(persisted.role()).isEqualTo(GroupRole.MEMBER);
        }
    }
}
