package app.weavora.zhijie.controlplane.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 仅为已提交 outbox 分配同步游标，不持锁执行网络发送或业务写入。 */
@Service
public class SyncSequenceAllocator {
    private final JdbcTemplate sql;

    public SyncSequenceAllocator(JdbcTemplate sql) { this.sql = sql; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void assignCommitted() {
        // 跨进程串行分配；独立事务提交后才向读者暴露新的高水位。
        sql.execute("SELECT pg_advisory_xact_lock(2053654885, 1)");
        sql.update("""
                INSERT INTO sync_event_sequences (event_id)
                SELECT event.event_id FROM outbox_events event
                WHERE NOT EXISTS (SELECT 1 FROM sync_event_sequences assigned WHERE assigned.event_id=event.event_id)
                ORDER BY event.sequence
                """);
    }
}
