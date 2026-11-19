package app.weavora.zhijie.controlplane.grant;

import java.time.Instant;
import java.util.Optional;

public interface GrantRepository {
    void insert(ActivityGrant grant);
    Optional<ActivityGrant> findById(String grantId);
    Optional<ActivityGrant> findByTokenHash(String tokenHash);
    void revoke(String grantId, Instant revokedAt);
    void revokeMember(String activityId, String userId, Instant revokedAt);
    void revokeLease(String leaseId, Instant revokedAt);
    void revokeOtherNetworkKeys(String leaseId, byte[] publicKey, Instant revokedAt);
    int countOtherVoiceLeases(String leaseId);
    void lockChannelAdmission(int lockId);
    int countOtherNetworkLeases(String leaseId);
}
