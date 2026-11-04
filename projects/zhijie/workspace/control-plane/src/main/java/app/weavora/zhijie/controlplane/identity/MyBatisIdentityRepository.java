package app.weavora.zhijie.controlplane.identity;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisIdentityRepository implements IdentityRepository {
    private final IdentityMapper mapper;

    public MyBatisIdentityRepository(IdentityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<UserAccount> findUserByEmail(String emailNormalized) {
        return mapper.findUserByEmail(emailNormalized);
    }

    @Override
    public Optional<UserAccount> findUserById(String userId) {
        return mapper.findUserById(userId);
    }

    @Override
    public void insertUser(UserAccount user) {
        mapper.insertUser(user);
    }

    @Override
    public void updateUser(UserAccount user) {
        mapper.updateUser(user);
    }

    @Override
    public Optional<EmailChallenge> findLatestChallenge(String userId, ChallengePurpose purpose) {
        return mapper.findLatestChallenge(userId, purpose);
    }

    @Override
    public void insertChallenge(EmailChallenge challenge) {
        mapper.insertChallenge(challenge);
    }

    @Override
    public void updateChallenge(EmailChallenge challenge) {
        mapper.updateChallenge(challenge);
    }

    @Override
    public Optional<AuthSession> findSessionById(String sessionId) {
        return mapper.findSessionById(sessionId);
    }

    @Override
    public Optional<AuthSession> findSessionByAccessTokenHash(String accessTokenHash) {
        return mapper.findSessionByAccessTokenHash(accessTokenHash);
    }

    @Override
    public void insertSession(AuthSession session) {
        mapper.insertSession(session);
    }

    @Override
    public void updateSession(AuthSession session) {
        mapper.updateSession(session);
    }

    @Override
    public void revokeAllSessions(String userId, Instant revokedAt) {
        mapper.revokeAllSessions(userId, revokedAt);
    }
}
