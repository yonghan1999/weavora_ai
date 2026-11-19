package app.weavora.zhijie.controlplane.message;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.group.GroupRole;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

class MessageDeleteConcurrencyIntegrationTest {
    @Test
    void concurrentModeratorsReturnTheSameDeletionAndRecordItOnce() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=");
             var workers = Executors.newFixedThreadPool(2)) {
            var groups = context.getBean(GroupService.class);
            var messages = context.getBean(MessageService.class);
            var sql = context.getBean(JdbcTemplate.class);
            String owner = UUID.randomUUID().toString(), admin = UUID.randomUUID().toString(), author = UUID.randomUUID().toString();
            for (String user : List.of(owner, admin, author)) {
                context.getBean(IdentityRepository.class).insertUser(new UserAccount(user, user + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
            }
            String group = groups.createGroup(owner, "并发代删", null, "").id();
            for (String user : List.of(admin, author)) groups.acceptInvitation(user, groups.invite(owner, group, user).id());
            groups.changeRole(owner, group, admin, GroupRole.ADMIN);
            String id = messages.post(author, group, "待删除").id();
            try (var blocker = postgres.getPostgresDatabase().getConnection(); var lock = blocker.prepareStatement("SELECT id FROM messages WHERE id = ? FOR UPDATE")) {
                blocker.setAutoCommit(false);
                lock.setString(1, id);
                lock.executeQuery().close();
                var first = workers.submit(() -> messages.delete(owner, id, "owner-delete"));
                var second = workers.submit(() -> messages.delete(admin, id, "admin-delete"));
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    int waiting;
                    do {
                        waiting = sql.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%UPDATE messages SET deleted_at%'", Integer.class);
                        if (waiting == 2) break;
                        Thread.sleep(10);
                    } while (System.nanoTime() < deadline);
                    assertThat(waiting).as("both conditional updates reached the locked message").isEqualTo(2);
                } finally { blocker.rollback(); }
                var winner = first.get(8, TimeUnit.SECONDS);
                assertThat(second.get(8, TimeUnit.SECONDS)).isEqualTo(winner);
                assertThat(winner.deletedBy()).isIn(owner, admin);
                assertThat(winner.deleteKind()).isEqualTo(DeleteKind.MODERATOR);
                assertThat(winner.body()).isNull();
                assertThat(messages.list(author, group, null, 10)).containsExactly(winner);
                assertThat(sql.queryForList("SELECT actor_id FROM audit_logs WHERE target_id=? AND action='MESSAGE_DELETE'", String.class, id)).containsExactly(winner.deletedBy());
                assertThat(sql.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND type='message.deleted'", Integer.class, id)).isEqualTo(1);
            }
        }
    }
}
