package app.weavora.zhijie.controlplane.sync;

import app.weavora.zhijie.controlplane.identity.IdentityService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SyncCursorController {
    private final IdentityService identity;
    private final OutboxRepository outbox;

    public SyncCursorController(IdentityService identity, OutboxRepository outbox) {
        this.identity = identity; this.outbox = outbox;
    }

    @GetMapping("/api/v1/sync/cursor")
    public Map<String, Map<String, Long>> cursor(@RequestHeader("Authorization") String authorization) {
        identity.authenticate(authorization);
        // 先取得高水位，再由客户端读取权威快照并从该点建立增量连接。
        return Map.of("data", Map.of("cursor", outbox.latestSequence()));
    }
}
