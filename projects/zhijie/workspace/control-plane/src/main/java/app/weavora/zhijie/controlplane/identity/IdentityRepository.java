package app.weavora.zhijie.controlplane.identity;

import java.time.Instant;
import java.util.Optional;

public interface IdentityRepository {
    Optional<UserAccount> findUserByEmail(String emailNormalized);

    Optional<UserAccount> findUserById(String userId);

    void insertUser(UserAccount user);

    void updateUser(UserAccount user);

    Optional<EmailChallenge> findLatestChallenge(String userId, ChallengePurpose purpose);

    void insertChallenge(EmailChallenge challenge);

    void updateChallenge(EmailChallenge challenge);

    Optional<AuthSession> findSessionById(String sessionId);

    Optional<AuthSession> findSessionByAccessTokenHash(String accessTokenHash);

    void insertSession(AuthSession session);

    void updateSession(AuthSession session);

    void revokeAllSessions(String userId, Instant revokedAt);
}
