package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.DriverManager;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SyncCommitOrderIntegrationTest {
    @Test void laterCommitRemainsReplayableAfterCursorAdvancedPastEarlierCommittedTransaction() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var events = context.getBean(OutboxService.class);
            var sync = context.getBean(SyncService.class);
            var transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var inserted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var workers = Executors.newSingleThreadExecutor()) {
                var delayed = workers.submit(() -> transactions.execute(status -> {
                    var event = events.record(null, null, "delayed", "test.changed", "{}", null, null);
                    inserted.countDown();
                    try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    return event.eventId();
                }));
                String early;
                long cursor;
                try {
                    assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
                    early = transactions.execute(status -> events.record(null, null, "early", "test.changed", "{}", null, null).eventId());
                    var first = sync.history("test-user", 0);
                    assertThat(first).extracting(SyncEvent::eventId).containsExactly(early);
                    cursor = first.getLast().sequence();
                } finally { release.countDown(); }
                String late = delayed.get(5, TimeUnit.SECONDS);
                var next = sync.history("test-user", cursor);
                assertThat(next).as("committed-later event must not disappear behind the acknowledged cursor")
                        .extracting(SyncEvent::eventId).containsExactly(late);
                assertThat(next.getFirst().sequence()).isGreaterThan(cursor);
                assertThat(sync.history("test-user", 0)).extracting(SyncEvent::eventId).containsExactly(early, late);
                String rolledBack = transactions.execute(status -> {
                    var event = events.record(null, null, "rollback", "test.changed", "{}", null, null);
                    // 同一业务事务内读取也不能提前公开尚未提交的事件。
                    assertThat(sync.history("test-user", 0)).extracting(SyncEvent::eventId).doesNotContain(event.eventId());
                    status.setRollbackOnly();
                    return event.eventId();
                });
                String afterRollback = transactions.execute(status -> events.record(null, null, "after-rollback", "test.changed", "{}", null, null).eventId());
                assertThat(sync.history("test-user", cursor)).extracting(SyncEvent::eventId)
                        .containsExactly(late, afterRollback).doesNotContain(rolledBack);
            }
        }
    }

    @Test void migrationPreservesExistingCursorsIncludingGapsAndContinuesBeyondHighWatermark() throws Exception {
        try (var postgres = EmbeddedPostgres.start()) {
            String url = postgres.getJdbcUrl("postgres", "postgres");
            Flyway.configure().dataSource(url, "postgres", "").target("13").load().migrate();
            try (var connection = DriverManager.getConnection(url, "postgres", ""); var sql = connection.createStatement()) {
                sql.execute("INSERT INTO outbox_events (sequence,event_id,aggregate_id,type,payload,occurred_at,attempts) "
                        + "VALUES (3,'old-3','test','test.changed','{}',NOW(),0),(9,'old-9','test','test.changed','{}',NOW(),0)");
            }
            Flyway.configure().dataSource(url, "postgres", "").load().migrate();
            try (var context = new SpringApplication(ControlPlaneApplication.class).run(
                    "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                    "--spring.datasource.url=" + url, "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
                var sync = context.getBean(SyncService.class);
                var events = context.getBean(OutboxService.class);
                assertThat(sync.history("test-user", 0)).extracting(SyncEvent::sequence).containsExactly(3L, 9L);
                assertThat(sync.history("test-user", 3)).extracting(SyncEvent::eventId).containsExactly("old-9");
                String fresh = events.record(null, null, "new", "test.changed", "{}", null, null).eventId();
                var next = sync.history("test-user", 9);
                assertThat(next).extracting(SyncEvent::eventId).containsExactly(fresh);
                assertThat(next.getFirst().sequence()).isGreaterThan(9);
            }
        }
    }
}
