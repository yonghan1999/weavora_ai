package app.weavora.zhijie.controlplane.sync;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 与业务同事务追加最小失效通知，客户端重新读取当前有权访问的资源。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ProductSync {
    private final OutboxService outbox;

    public ProductSync(OutboxService outbox) { this.outbox = outbox; }

    public void groupChanged(String groupId, String type) {
        outbox.record(groupId, null, groupId, type, "{}", null, null);
    }

    public void userChanged(String userId) {
        // aggregateId 仅在服务端鉴权使用，外发事件不包含账号标识或已失权群资料。
        outbox.record(null, null, userId, "user.changed", "{}", null, null);
    }
}
