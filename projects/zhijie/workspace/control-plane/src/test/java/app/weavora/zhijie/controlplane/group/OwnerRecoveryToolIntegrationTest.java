package app.weavora.zhijie.controlplane.group;

import static org.assertj.core.api.Assertions.*;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.sync.OutboxRepository;
import app.weavora.zhijie.controlplane.sync.SyncService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

class OwnerRecoveryToolIntegrationTest {
    @Test
    void offlineHumanReviewIsAtomicCurrentAndSeparatedFromRuntime() throws Exception {
        try (var postgres = EmbeddedPostgres.start()) {
            String adminUrl = postgres.getJdbcUrl("postgres", "postgres");
            try (var admin = DriverManager.getConnection(adminUrl, "postgres", "")) {
                admin.createStatement().execute(Files.readString(Path.of("ops/database/bootstrap-roles.sql")));
                admin.createStatement().execute("ALTER ROLE zhijie_migrator LOGIN; ALTER ROLE zhijie_app LOGIN; ALTER ROLE zhijie_support LOGIN");
            }
            String migratorUrl = postgres.getJdbcUrl("zhijie_migrator", "postgres");
            Flyway.configure().dataSource(migratorUrl, "zhijie_migrator", "").load().migrate();
            try (var migrator = DriverManager.getConnection(migratorUrl, "zhijie_migrator", "")) {
                for (String file : List.of("ops/database/grant-runtime.sql", "ops/database/grant-support.sql")) {
                    migrator.createStatement().execute(Files.readString(Path.of(file)));
                }
            }
            String appUrl = postgres.getJdbcUrl("zhijie_app", "postgres");
            String supportUrl = postgres.getJdbcUrl("zhijie_support", "postgres");
            try (var context = new SpringApplication(ControlPlaneApplication.class).run(
                    "--server.address=127.0.0.1", "--server.port=0", "--spring.profiles.active=runtime",
                    "--spring.datasource.url=" + appUrl, "--spring.datasource.username=zhijie_app",
                    "--spring.datasource.password=", "--zhijie.scheduling.enabled=false")) {
                var groups = context.getBean(GroupService.class);
                var sql = context.getBean(JdbcTemplate.class);
                var outbox = context.getBean(OutboxRepository.class);
                var sync = context.getBean(SyncService.class);
                var users = context.getBean(IdentityRepository.class);
                String owner = user(users), target = user(users), other = user(users);
                var group = groups.createGroup(owner, "人工恢复", null, "");
                groups.acceptInvitation(target, groups.invite(owner, group.id(), target).id());
                groups.acceptInvitation(other, groups.invite(owner, group.id(), other).id());
                var pendingTransfer = groups.initiateTransfer(owner, group.id(), other);
                String id = groups.requestRecovery(target, group.id(), "support-ticket-1").id();
                var review = review(id, owner, target, "RECOVER");
                // 用真实命令入口执行默认预检，证明不会启动服务或自动提交。
                Path reviewFile = Files.createTempFile(Path.of("target"), "owner-review-", ".json");
                try {
                    Files.writeString(reviewFile, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(review));
                    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                    String packaged = System.getenv("ZHIJIE_TEST_RECOVERY_JAR");
                    var processBuilder = packaged == null
                            ? new ProcessBuilder(java, "-cp", commandClasspath(), ControlPlaneApplication.class.getName(), "--owner-recovery-review", reviewFile.toString())
                            : new ProcessBuilder(java, "-jar", packaged, "--owner-recovery-review", reviewFile.toString());
                    processBuilder.environment().put("ZHIJIE_SUPPORT_JDBC_URL", supportUrl);
                    processBuilder.environment().put("ZHIJIE_SUPPORT_DB_USERNAME", "zhijie_support");
                    processBuilder.environment().put("ZHIJIE_SUPPORT_DB_PASSWORD", "test-unused");
                    Path output = Files.createTempFile(Path.of("target"), "owner-review-cli-", ".log");
                    var process = processBuilder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
                    try {
                        assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
                        assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
                        assertThat(Files.readString(output)).contains("OWNER_RECOVERY_REVIEW_VALIDATED_ROLLED_BACK").doesNotContain("Tomcat started");
                    } finally { if (process.isAlive()) process.destroyForcibly(); }
                } finally { Files.deleteIfExists(reviewFile); }
                try (var support = DriverManager.getConnection(supportUrl, "zhijie_support", "")) {
                    for (String decision : List.of("DISPUTED", "INSUFFICIENT_EVIDENCE", "REJECT")) {
                        String rejected = groups.requestRecovery(target, group.id(), "support-ticket-reject").id();
                        long beforeDecision = outbox.latestSequence();
                        OwnerRecoveryTool.review(support, review(rejected, owner, target, decision), true);
                        assertThat(sync.history(target, beforeDecision)).singleElement().satisfies(event -> {
                            assertThat(event.type()).isEqualTo("group.changed");
                            assertThat(event.payload()).isEqualTo("{}");
                        });
                        assertThat(groups.getGroup(owner, group.id()).ownerUserId()).isEqualTo(owner);
                        assertThat(sql.queryForObject("SELECT status FROM owner_recovery_requests WHERE id=?", String.class, rejected)).isEqualTo("REJECTED");
                    }
                    assertThatThrownBy(() -> OwnerRecoveryTool.review(support,
                            new OwnerRecoveryTool.Review(id, owner, target, target, "ticket-decision", "RECOVER", true, true, true), true))
                            .hasMessage("INDEPENDENT_REVIEWER_REQUIRED");
                    assertThatThrownBy(() -> OwnerRecoveryTool.review(support,
                            new OwnerRecoveryTool.Review(id, owner, target, "support-agent", "ticket-decision", "RECOVER", true, false, true), true))
                            .hasMessage("HUMAN_EVIDENCE_CONFIRMATIONS_REQUIRED");
                    long cursor = outbox.latestSequence();
                    OwnerRecoveryTool.review(support, review, false);
                    assertThat(outbox.latestSequence()).isEqualTo(cursor);
                    assertThat(groups.getGroup(owner, group.id()).ownerUserId()).isEqualTo(owner);
                    assertThat(sql.queryForObject("SELECT status FROM owner_recovery_requests WHERE id=?", String.class, id)).isEqualTo("PENDING");
                    // 审计插入失败必须回滚已经执行的群主与角色更新。
                    try (var migrator = DriverManager.getConnection(migratorUrl, "zhijie_migrator", "")) {
                        migrator.createStatement().execute("REVOKE INSERT ON audit_logs FROM zhijie_support");
                    }
                    assertThatThrownBy(() -> OwnerRecoveryTool.review(support, review, true)).isInstanceOf(java.sql.SQLException.class);
                    assertThat(outbox.latestSequence()).isEqualTo(cursor);
                    assertThat(groups.getGroup(owner, group.id()).ownerUserId()).isEqualTo(owner);
                    assertThat(sql.queryForObject("SELECT status FROM owner_recovery_requests WHERE id=?", String.class, id)).isEqualTo("PENDING");
                    try (var migrator = DriverManager.getConnection(migratorUrl, "zhijie_migrator", "")) {
                        migrator.createStatement().execute("GRANT INSERT ON audit_logs TO zhijie_support");
                    }
                    // 群主账号失效不会阻止有证据的人工作业；不恢复其账号、会话或成员权限。
                    sql.update("UPDATE users SET status='DISABLED' WHERE id=?", owner);
                    OwnerRecoveryTool.review(support, review, true);
                    assertOwner(sql, group.id(), target);
                    assertThat(sync.history(target, cursor)).singleElement().satisfies(event -> {
                        assertThat(event.type()).isEqualTo("group.changed");
                        assertThat(event.groupId()).isEqualTo(group.id());
                        assertThat(event.payload()).isEqualTo("{}");
                    });
                    assertThat(sync.history(other, cursor)).isEqualTo(sync.history(target, cursor));
                    assertThat(sync.history(user(users), cursor)).isEmpty();
                    assertThat(sql.queryForObject("SELECT status FROM owner_transfers WHERE id=?", String.class, pendingTransfer.id())).isEqualTo("CANCELLED");
                    assertThatThrownBy(() -> OwnerRecoveryTool.review(support, review, true)).hasMessage("RECOVERY_ALREADY_DECIDED");
                    assertThat(sync.history(target, cursor)).hasSize(1);
                    try (var access = support.createStatement(); var result = access.executeQuery(
                            "SELECT has_table_privilege(current_user, 'outbox_events', 'INSERT'), "
                            + "has_function_privilege(current_user, 'public.notify_owner_review_group_change()', 'EXECUTE')")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getBoolean(1)).isFalse();
                        assertThat(result.getBoolean(2)).isFalse();
                    }
                    assertThat(sql.queryForObject("SELECT count(*) FROM audit_logs WHERE target_id=? AND action='OWNER_RECOVERY_REVIEW'", Integer.class, id)).isEqualTo(1);
                    for (String denied : List.of("SELECT password_hash FROM users", "UPDATE users SET status='ACTIVE'",
                            "SELECT * FROM auth_sessions", "DELETE FROM audit_logs", "UPDATE audit_logs SET result='forged'")) {
                        assertThatThrownBy(() -> support.createStatement().execute(denied)).isInstanceOf(java.sql.SQLException.class);
                    }
                }
                // 长期运行账号不能伪造支持决定，也不能成为支持账号。
                for (String denied : List.of("UPDATE owner_recovery_requests SET status='RESOLVED'", "SET ROLE zhijie_support",
                        "INSERT INTO owner_recovery_requests(id,group_id,requester_id,evidence_ref,created_at,status) SELECT 'forged',group_id,requester_id,evidence_ref,created_at,'RESOLVED' FROM owner_recovery_requests LIMIT 1")) {
                    assertThatThrownBy(() -> sql.execute(denied)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                }
                String stale = groups.requestRecovery(other, group.id(), "ticket-stale").id();
                groups.acceptTransfer(other, groups.initiateTransfer(target, group.id(), other).id());
                try (var support = DriverManager.getConnection(supportUrl, "zhijie_support", "")) {
                    assertThatThrownBy(() -> OwnerRecoveryTool.review(support, review(stale, target, target, "RECOVER"), true))
                            .hasMessage("OWNER_CHANGED_SINCE_REQUEST");
                    String legacy = groups.requestRecovery(target, group.id(), "ticket-legacy").id();
                    try (var migrator = DriverManager.getConnection(migratorUrl, "zhijie_migrator", "")) {
                        try (var statement = migrator.prepareStatement("UPDATE owner_recovery_requests SET expected_owner_user_id=NULL WHERE id=?")) {
                            statement.setString(1, legacy); statement.executeUpdate();
                        }
                    }
                    assertThatThrownBy(() -> OwnerRecoveryTool.review(support, review(legacy, other, target, "RECOVER"), true)).hasMessage("OWNER_CHANGED_SINCE_REQUEST");
                    OwnerRecoveryTool.review(support, review(legacy, other, target, "INSUFFICIENT_EVIDENCE"), true);
                }
                String race = groups.requestRecovery(target, group.id(), "ticket-race").id();
                try (var executor = Executors.newFixedThreadPool(2)) {
                    var start = new CountDownLatch(1);
                    Callable<String> task = () -> {
                        start.await();
                        try (var support = DriverManager.getConnection(supportUrl, "zhijie_support", "")) {
                            OwnerRecoveryTool.review(support, review(race, other, target, "RECOVER"), true);
                            return "COMMITTED";
                        } catch (IllegalArgumentException failure) { return failure.getMessage(); }
                    };
                    var a = executor.submit(task); var b = executor.submit(task); start.countDown();
                    assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                            .containsExactlyInAnyOrder("COMMITTED", "RECOVERY_ALREADY_DECIDED");
                }
                assertOwner(sql, group.id(), target);
                String gone = groups.requestRecovery(other, group.id(), "ticket-gone").id();
                groups.leaveGroup(other, group.id());
                try (var support = DriverManager.getConnection(supportUrl, "zhijie_support", "")) {
                    assertThatThrownBy(() -> OwnerRecoveryTool.review(support, review(gone, target, other, "RECOVER"), true)).hasMessage("CURRENT_MEMBERSHIP_REQUIRED");
                    groups.dissolveGroup(target, group.id());
                    assertThatThrownBy(() -> OwnerRecoveryTool.review(support, review(gone, target, other, "REJECT"), true)).hasMessage("GROUP_NOT_AVAILABLE");
                }
            }
        }
    }

    private static OwnerRecoveryTool.Review review(String id, String owner, String target, String decision) {
        return new OwnerRecoveryTool.Review(id, owner, target, "support-agent", "ticket-decision", decision, true, true, true);
    }

    private static String user(IdentityRepository users) {
        String id = UUID.randomUUID().toString();
        users.insertUser(new UserAccount(id, id + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
        return id;
    }

    private static String commandClasspath() throws Exception {
        // Maven 当前 JVM 的 class.path 不包含插件测试依赖，使用实际测试类加载器的 URL。
        var loader = OwnerRecoveryToolIntegrationTest.class.getClassLoader();
        if (loader instanceof java.net.URLClassLoader urls) {
            return java.util.Arrays.stream(urls.getURLs()).map(url -> {
                try { return Path.of(url.toURI()).toString(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        }
        return System.getProperty("java.class.path");
    }

    private static void assertOwner(JdbcTemplate sql, String group, String owner) {
        assertThat(sql.queryForObject("SELECT owner_user_id FROM groups WHERE id=?", String.class, group)).isEqualTo(owner);
        assertThat(sql.queryForList("SELECT user_id FROM group_members WHERE group_id=? AND status='ACTIVE' AND role='OWNER'", String.class, group)).containsExactly(owner);
    }
}
