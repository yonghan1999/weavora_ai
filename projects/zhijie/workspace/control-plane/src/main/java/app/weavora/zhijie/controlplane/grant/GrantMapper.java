package app.weavora.zhijie.controlplane.grant;

import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GrantMapper {
    void insert(ActivityGrant grant);
    Optional<ActivityGrant> findById(String grantId);
    Optional<ActivityGrant> findByTokenHash(String tokenHash);
    void revoke(@Param("grantId") String grantId, @Param("revokedAt") Instant revokedAt);
    void revokeMember(@Param("activityId") String activityId, @Param("userId") String userId, @Param("revokedAt") Instant revokedAt);
    void revokeLease(@Param("leaseId") String leaseId, @Param("revokedAt") Instant revokedAt);
}
