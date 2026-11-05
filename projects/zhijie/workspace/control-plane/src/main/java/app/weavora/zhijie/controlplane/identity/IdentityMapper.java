package app.weavora.zhijie.controlplane.identity;

import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IdentityMapper {
    Optional<UserAccount> findUserByEmail(String emailNormalized);

    Optional<UserAccount> findUserById(String userId);

    void insertUser(UserAccount user);

    void updateUser(UserAccount user);

    Optional<EmailChallenge> findLatestChallenge(@Param("userId") String userId, @Param("purpose") ChallengePurpose purpose);

    void insertChallenge(EmailChallenge challenge);

    void updateChallenge(EmailChallenge challenge);

    Optional<AuthSession> findSessionById(String sessionId);

    Optional<AuthSession> findSessionByAccessTokenHash(String accessTokenHash);

    void insertSession(AuthSession session);

    void updateSession(AuthSession session);

    void revokeAllSessions(@Param("userId") String userId, @Param("revokedAt") Instant revokedAt);
}
