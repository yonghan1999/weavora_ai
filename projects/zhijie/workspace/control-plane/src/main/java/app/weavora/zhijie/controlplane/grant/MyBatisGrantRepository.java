package app.weavora.zhijie.controlplane.grant;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisGrantRepository implements GrantRepository {
    private final GrantMapper mapper;
    public MyBatisGrantRepository(GrantMapper mapper) { this.mapper = mapper; }
    public void insert(ActivityGrant grant) { mapper.insert(grant); }
    public Optional<ActivityGrant> findById(String grantId) { return mapper.findById(grantId); }
    public Optional<ActivityGrant> findByTokenHash(String tokenHash) { return mapper.findByTokenHash(tokenHash); }
    public void revoke(String grantId, Instant revokedAt) { mapper.revoke(grantId, revokedAt); }
    public void revokeMember(String activityId, String userId, Instant revokedAt) { mapper.revokeMember(activityId, userId, revokedAt); }
    public void revokeLease(String leaseId, Instant revokedAt) { mapper.revokeLease(leaseId, revokedAt); }
}
