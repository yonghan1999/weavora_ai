package app.weavora.zhijie.controlplane.group;

import static org.assertj.core.api.Assertions.assertThat;
import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.identity.*;
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
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class GroupBrowserIntegrationTest {
    private final Path workspace = Path.of(System.getProperty("user.dir")).getParent();
    private final Path ui = workspace.resolve("desktop/ui");

    private Path clientExecutable;

    private void prepareClients() throws Exception {
        var build = new ProcessBuilder("node", "scripts/build.mjs").directory(ui.toFile()).inheritIO().start();
        try {
            assertThat(build.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(build.exitValue()).isZero();
        } finally { build.destroyForcibly(); }
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
                "--spring.web.resources.static-locations=" + ui.resolve("dist").toUri(),
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=");
    }

    private void runBrowser(ServletWebServerApplicationContext context, String password, String scenario) throws Exception {
        var profile = Files.createTempDirectory(workspace.resolve("target"), "group-browser-" + scenario + "-");
        var browser = new ProcessBuilder("node", "tests/group-browser.mjs", profile.toString(), clientExecutable.toString())
                .directory(ui.toFile()).redirectErrorStream(true).redirectOutput(profile.resolve("scenario.log").toFile()).start();
        try {
            try (var input = browser.getOutputStream()) {
                context.getBean(ObjectMapper.class).writeValue(input, Map.of("port", context.getWebServer().getPort(), "password", password, "scenario", scenario));
            }
            assertThat(browser.waitFor(250, TimeUnit.SECONDS)).as("browser deadline: %s", profile).isTrue();
            assertThat(browser.exitValue()).as("browser steps: %s", profile.resolve("scenario.log")).isZero();
        } finally {
            browser.descendants().forEach(ProcessHandle::destroyForcibly);
            browser.destroyForcibly();
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
            assertThat(sql.queryForObject("SELECT count(*) FROM connection_leases WHERE activity_id=? AND user_id=? AND revoked_at IS NULL AND member_generation=1", Integer.class, activity.id(), owner)).isOne();
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
            assertThat(groups.findGroup(group.id()).orElseThrow().ownerUserId()).isEqualTo(owner);
            assertThat(groups.findMember(group.id(), owner).orElseThrow().role()).isEqualTo(GroupRole.OWNER);
            var persisted = groups.findMember(group.id(), member).orElseThrow();
            assertThat(persisted.status()).isEqualTo(MemberStatus.REMOVED);
            assertThat(persisted.role()).isEqualTo(GroupRole.MEMBER);
        }
    }
}
