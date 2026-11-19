package app.weavora.zhijie.controlplane.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.message.MessageService;
import app.weavora.zhijie.controlplane.room.RoomService;
import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.apache.coyote.AbstractProtocol;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class RuntimeDatabasePrivilegesIntegrationTest {
    @Test
    void runtimeCanPerformBusinessAndAppendAuditButCannotAlterHistoryOrSchema() throws Exception {
        try (var postgres = EmbeddedPostgres.start()) {
            String adminUrl = postgres.getJdbcUrl("postgres", "postgres");
            try (var admin = DriverManager.getConnection(adminUrl, "postgres", "")) {
                executeFile(admin, "ops/database/bootstrap-roles.sql");
                admin.createStatement().execute("ALTER ROLE zhijie_migrator LOGIN; ALTER ROLE zhijie_app LOGIN");
            }
            String migratorUrl = postgres.getJdbcUrl("zhijie_migrator", "postgres");
            String password = UUID.randomUUID().toString();
            assertThatThrownBy(() -> DatabaseMigration.run(Map.of()))
                    .hasMessage("MIGRATION_DATABASE_CONFIGURATION_REQUIRED");
            try (var admin = DriverManager.getConnection(adminUrl, "postgres", "")) {
                admin.createStatement().execute("ALTER ROLE zhijie_migrator CREATEDB");
                assertThatThrownBy(() -> migrate(migratorUrl, password, false))
                        .hasMessageContaining("MIGRATION_DATABASE_ROLE_REQUIRED");
                admin.createStatement().execute("ALTER ROLE zhijie_migrator NOCREATEDB");
            }
            migrate(migratorUrl, password, false);
            migrate(migratorUrl, password, true);
            try (var migrator = DriverManager.getConnection(migratorUrl, "zhijie_migrator", "")) {
                executeFile(migrator, "ops/database/grant-runtime.sql");
            }
            assertThatThrownBy(() -> new SpringApplication(ControlPlaneApplication.class).run(
                    "--server.address=127.0.0.1", "--server.port=0", "--spring.profiles.active=runtime",
                    "--spring.datasource.url=" + adminUrl, "--spring.datasource.username=postgres",
                    "--spring.datasource.password=", "--zhijie.scheduling.enabled=false"))
                    .hasStackTraceContaining("RUNTIME_DATABASE_ROLE_EXCESSIVE");
            String appUrl = postgres.getJdbcUrl("zhijie_app", "postgres");
            try (var context = new SpringApplication(ControlPlaneApplication.class).run(
                    "--server.port=0", "--spring.profiles.active=runtime",
                    "--spring.datasource.url=" + appUrl, "--spring.datasource.username=zhijie_app",
                    "--spring.datasource.password=", "--zhijie.scheduling.enabled=false")) {
                var server = (TomcatWebServer)((ServletWebServerApplicationContext)context).getWebServer();
                var protocol = (AbstractProtocol<?>)server.getTomcat().getConnector().getProtocolHandler();
                assertThat(protocol.getAddress()).isEqualTo(InetAddress.getByName("127.0.0.1"));
                assertThat(protocol.getLocalPort()).isPositive();
                var sql = context.getBean(JdbcTemplate.class);
                assertThat(sql.queryForObject("SELECT current_user", String.class)).isEqualTo("zhijie_app");
                assertThat(sql.queryForObject("SELECT tableowner FROM pg_tables WHERE tablename='audit_logs'", String.class))
                        .isEqualTo("zhijie_migrator");
                assertThat(sql.queryForList("SELECT rolname FROM pg_roles WHERE rolname='zhijie_app' "
                        + "AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)", String.class)).isEmpty();
                assertThat(sql.queryForObject("SELECT pg_has_role(current_user, 'zhijie_migrator', 'MEMBER')", Boolean.class)).isFalse();
                String owner = UUID.randomUUID().toString();
                context.getBean(IdentityRepository.class).insertUser(new UserAccount(owner, owner + "@example.test",
                        AccountStatus.ACTIVE, "unused", Instant.now()));
                var groups = context.getBean(GroupService.class);
                var group = groups.createGroup(owner, "低权限实际业务", null, "");
                var rooms = context.getBean(RoomService.class);
                var room = rooms.createRoom(owner, group.id(), "权限房间", "", "", null);
                rooms.startActivity(owner, room.id());
                var joined = context.getBean(ActivityCoordinator.class).join(owner, room.id(), "device", null, "join");
                context.getBean(MessageService.class).post(owner, group.id(), "持久消息");
                context.getBean(ActivityCoordinator.class).leave(owner, joined.activityId());
                groups.updateGroup(owner, group.id(), "更新后", null, "");
                assertThat(sql.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isGreaterThan(3);
                for (String forbidden : List.of(
                        "UPDATE audit_logs SET result='ALTERED'", "DELETE FROM audit_logs", "TRUNCATE audit_logs",
                        "DROP TABLE audit_logs", "ALTER TABLE audit_logs ADD COLUMN forged TEXT",
                        "CREATE TABLE forbidden_table(id TEXT)", "CREATE TEMP TABLE forbidden_temp(id TEXT)",
                        "DELETE FROM flyway_schema_history", "TRUNCATE users", "SET ROLE zhijie_migrator",
                        "ALTER ROLE zhijie_app SUPERUSER", "UPDATE network_policy_revisions SET actor_ref='forged'")) {
                    assertThatThrownBy(() -> sql.execute(forbidden)).withFailMessage("Runtime must reject %s", forbidden)
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                }
                // PostgreSQL 对无 grant option 的 GRANT 可仅发警告；以实际权限和写入拒绝为准。
                sql.execute("GRANT UPDATE ON audit_logs TO zhijie_app");
                assertThat(sql.queryForObject("SELECT has_table_privilege(current_user, 'audit_logs', 'UPDATE')", Boolean.class)).isFalse();
                assertThatThrownBy(() -> sql.execute("UPDATE audit_logs SET result='ALTERED'"))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(groups.getGroup(owner, group.id()).name()).isEqualTo("更新后");
                try (var migrator = DriverManager.getConnection(migratorUrl, "zhijie_migrator", "")) {
                    migrator.createStatement().execute("REVOKE INSERT ON audit_logs FROM zhijie_app");
                }
                assertThatThrownBy(() -> groups.updateGroup(owner, group.id(), "应回滚", null, ""))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(groups.getGroup(owner, group.id()).name()).isEqualTo("更新后");
                try (var migrator = DriverManager.getConnection(migratorUrl, "zhijie_migrator", "")) {
                    migrator.createStatement().execute("GRANT INSERT ON audit_logs TO zhijie_app");
                }
                groups.dissolveGroup(owner, group.id());
                assertThat(sql.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isPositive();
                assertThat(sql.queryForObject("SELECT count(*) FROM audit_logs WHERE action='GROUP_DISSOLVE'", Integer.class)).isEqualTo(1);
            }
            // 新增表不自动授权；迁移历史失配时 runtime 必须停止启动而非跳过校验。
            try (var migrator = DriverManager.getConnection(migratorUrl, "zhijie_migrator", "")) {
                migrator.createStatement().execute("CREATE TABLE future_table(id TEXT)");
            }
            try (var app = DriverManager.getConnection(appUrl, "zhijie_app", "")) {
                assertThatThrownBy(() -> app.createStatement().execute("SELECT * FROM future_table"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
            try (var migrator = DriverManager.getConnection(migratorUrl, "zhijie_migrator", "")) {
                migrator.createStatement().execute("UPDATE flyway_schema_history SET checksum=0 WHERE version='12'");
            }
            assertThatThrownBy(() -> migrate(migratorUrl, password, false)).hasMessageContaining("checksum mismatch");
            assertThatThrownBy(() -> new SpringApplication(ControlPlaneApplication.class).run(
                    "--server.address=127.0.0.1", "--server.port=0", "--spring.profiles.active=runtime",
                    "--spring.datasource.url=" + appUrl, "--spring.datasource.username=zhijie_app",
                    "--spring.datasource.password=", "--zhijie.scheduling.enabled=false"))
                    .hasStackTraceContaining("checksum mismatch");
        }
    }

    private void executeFile(Connection connection, String file) throws Exception {
        try (var statement = connection.createStatement()) {
            Path path = Path.of(file);
            statement.execute(Files.readString(Files.isRegularFile(path) ? path : Path.of("..").resolve(path)));
        }
    }

    private void migrate(String url, String password, boolean upToDate) throws Exception {
        var settings = Map.of("ZHIJIE_MIGRATION_DATABASE_URL", url, "ZHIJIE_MIGRATION_DATABASE_PASSWORD", password);
        String jar = System.getenv("ZHIJIE_TEST_MIGRATION_JAR");
        if (jar == null) {
            DatabaseMigration.run(settings);
            return;
        }
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-XX:-UsePerfData", "-jar", jar, "--migrate-database");
        command.environment().putAll(settings);
        Path log = Files.createTempFile("migration-cli-", ".log");
        try {
            var child = command.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertThat(child.waitFor(40, TimeUnit.SECONDS)).isTrue();
                String output = Files.readString(log);
                assertThat(output.contains(password)).isFalse();
                assertThat(output).doesNotContain("Tomcat started");
                if (child.exitValue() != 0) throw new IllegalStateException(output);
                assertThat(output).containsPattern(upToDate ? "DATABASE_MIGRATION_OK applied=0\\b" : "DATABASE_MIGRATION_OK applied=[1-9]\\d*");
            } finally { if (child.isAlive()) child.destroyForcibly(); }
        } finally { Files.deleteIfExists(log); }
    }
}
