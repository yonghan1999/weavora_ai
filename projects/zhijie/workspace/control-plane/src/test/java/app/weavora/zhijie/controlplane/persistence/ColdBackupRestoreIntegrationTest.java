package app.weavora.zhijie.controlplane.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.audit.AuditService;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.message.MessageService;
import app.weavora.zhijie.controlplane.message.MessageView;
import app.weavora.zhijie.controlplane.room.RoomService;
import app.weavora.zhijie.controlplane.sync.SyncEvent;
import app.weavora.zhijie.controlplane.sync.SyncService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class ColdBackupRestoreIntegrationTest {
    @Test void stoppedDatabaseCopyRestoresBusinessDataSessionsAndSyncSequences(@TempDir Path databaseCopies) throws Exception {
        var workspace = Path.of(System.getProperty("user.dir")).getParent();
        var evidence = Files.createTempDirectory(workspace.resolve("target"), "cold-restore-");
        var source = databaseCopies.resolve("source");
        var backup = databaseCopies.resolve("backup");
        var restored = databaseCopies.resolve("restored");
        String user = UUID.randomUUID().toString();
        String groupId;
        String roomId;
        String messageId;
        AuthTokens tokens;
        long cursor;
        List<String> events;
        try (var database = database(source); var context = application(database)) {
            var identity = context.getBean(IdentityService.class);
            context.getBean(IdentityRepository.class).insertUser(new UserAccount(user, "cold-restore@example.test",
                    AccountStatus.ACTIVE, context.getBean(CredentialProtector.class).hashPassword("cold-restore-password-123"), Instant.now()));
            tokens = identity.login("cold-restore@example.test", "cold-restore-password-123", "restore-device");
            var group = context.getBean(GroupService.class).createGroup(user, "冷备恢复群", null, "保留资料");
            groupId = group.id();
            var room = context.getBean(RoomService.class).createRoom(user, groupId, "恢复房间", "保留配置", "测试游戏", null);
            roomId = room.id();
            messageId = context.getBean(MessageService.class).post(user, groupId, "恢复前消息").id();
            var sync = context.getBean(SyncService.class);
            var history = sync.history(user, 0);
            cursor = history.getLast().sequence();
            events = history.stream().map(SyncEvent::eventId).toList();
            assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(14);
        }
        // 关闭应用和 PostgreSQL 后才能复制；不读取现有试用库或其他用户目录。
        assertThat(Files.exists(source.resolve("postmaster.pid"))).as("Source database cleanly stopped").isFalse();
        var before = hashes(source);
        assertThat(before.size()).isGreaterThan(100);
        var tool = workspace.resolve("ops/database/cold-backup.py");
        var copied = new ProcessBuilder("python", tool.toString(), "snapshot", "--source", source.toString(),
                "--destination", backup.toString()).redirectErrorStream(true).redirectOutput(evidence.resolve("snapshot.log").toFile()).start();
        assertThat(copied.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).as("Backup tool deadline").isTrue();
        assertThat(copied.exitValue()).as("Backup tool succeeded").isZero();
        try (var paths = Files.walk(backup.resolve("data"))) {
            for (var path : paths.sorted().toList()) {
                assertThat(Files.isSymbolicLink(path)).as("No external tablespace or symbolic link in fixture").isFalse();
                var destination = restored.resolve(backup.resolve("data").relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        assertThat(hashes(restored)).as("Every cold-copy file matches before database startup").isEqualTo(before);
        try (var original = Files.walk(source); var recovered = Files.walk(restored)) {
            assertThat(recovered.filter(Files::isDirectory).map(restored::relativize).toList())
                    .as("The independent restore preserves the stopped source directories, including empty ones")
                    .containsExactlyInAnyOrderElementsOf(original.filter(Files::isDirectory).map(source::relativize).toList());
        }
        var manifest = new ArrayList<String>();
        before.forEach((path, hash) -> manifest.add(hash + "  " + path));
        Files.write(evidence.resolve("backup-sha256.txt"), manifest);
        int files = before.size();
        try (var database = database(restored); var context = application(database)) {
            var identity = context.getBean(IdentityService.class);
            assertThat(identity.authenticate("Bearer " + tokens.accessToken()).id()).isEqualTo(user);
            assertThat(identity.login("cold-restore@example.test", "cold-restore-password-123", "after-restore").userId()).isEqualTo(user);
            assertThat(context.getBean(GroupService.class).getGroup(user, groupId).name()).isEqualTo("冷备恢复群");
            assertThat(context.getBean(RoomService.class).getRoom(user, roomId).description()).isEqualTo("保留配置");
            var messages = context.getBean(MessageService.class);
            assertThat(messages.list(user, groupId, null, 50)).extracting(MessageView::id).contains(messageId);
            assertThat(context.getBean(AuditService.class).list(user, groupId, null, 50)).extracting("action").contains("GROUP_CREATE");
            var sync = context.getBean(SyncService.class);
            assertThat(sync.history(user, 0)).extracting(SyncEvent::eventId).containsExactlyElementsOf(events);
            assertThat(sync.history(user, cursor)).isEmpty();
            String newMessage = messages.post(user, groupId, "恢复后消息").id();
            var next = sync.history(user, cursor);
            assertThat(next).hasSize(1);
            assertThat(next.getFirst().sequence()).isGreaterThan(cursor);
            assertThat(next.getFirst().payload()).contains(newMessage);
            var rotated = identity.refresh(tokens.refreshToken());
            assertThat(context.getBean(IdentityRepository.class).findSessionById(tokens.sessionId()).orElseThrow().generation()).isEqualTo(2);
            identity.logout(rotated.accessToken());
            assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(14);
        }
        assertThat(hashes(source)).as("Restore exercise never changes stopped source").isEqualTo(before);
        assertThat(hashes(backup.resolve("data"))).as("Retained backup stays immutable after restore startup").isEqualTo(before);
        var verified = new ProcessBuilder("python", tool.toString(), "verify", "--backup", backup.toString(), "--require-directories")
                .redirectErrorStream(true).redirectOutput(evidence.resolve("verify.log").toFile()).start();
        assertThat(verified.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(verified.exitValue()).as("Retained backup remains verifiable").isZero();
        Files.writeString(evidence.resolve("summary.json"), """
                {"scope":"same-host-stopped-postgres-copy","files":%d,"hashMatch":true,
                "restoredBusinessData":true,"restoredSession":true,"restoredSyncCursor":true,
                "postRestoreWrites":true,"targetServerRestore":"unverified"}
                """.formatted(files));
        System.out.println("COLD_RESTORE_EVIDENCE " + evidence);
    }

    private EmbeddedPostgres database(Path directory) throws Exception {
        return EmbeddedPostgres.builder().setDataDirectory(directory).setCleanDataDirectory(false).start();
    }

    private ConfigurableApplicationContext application(EmbeddedPostgres database) {
        var app = new SpringApplication(ControlPlaneApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run("--zhijie.scheduling.enabled=false", "--zhijie.mail.enabled=false",
                "--spring.datasource.url=" + database.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=");
    }

    private Map<String, String> hashes(Path root) throws Exception {
        var result = new TreeMap<String, String>();
        var digest = MessageDigest.getInstance("SHA-256");
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(path).toString().replace('\\', '/'), HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }
}
