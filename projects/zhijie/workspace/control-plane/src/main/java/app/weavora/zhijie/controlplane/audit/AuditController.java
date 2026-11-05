package app.weavora.zhijie.controlplane.audit;

import app.weavora.zhijie.controlplane.identity.IdentityService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuditController {
    private final AuditService audit;
    private final IdentityService identity;

    public AuditController(AuditService audit, IdentityService identity) {
        this.audit = audit;
        this.identity = identity;
    }

    @GetMapping("/groups/{groupId}/audit-logs")
    public Map<String, List<AuditLog>> list(@RequestHeader("Authorization") String auth, @PathVariable String groupId,
                                            @RequestParam(required = false) Instant before,
                                            @RequestParam(defaultValue = "50") int limit) {
        return Map.of("data", audit.list(identity.authenticate(auth).id(), groupId, before, limit));
    }
}
