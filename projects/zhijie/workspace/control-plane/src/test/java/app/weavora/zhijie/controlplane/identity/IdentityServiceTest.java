package app.weavora.zhijie.controlplane.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdentityServiceTest {
    private static final String EMAIL = "Player@Example.com";
    private static final String PASSWORD = "correct-horse-battery";

    private InMemoryIdentityRepository repository;
    private FakeEmailDispatch emailDispatch;
    private IdentityService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryIdentityRepository();
        emailDispatch = new FakeEmailDispatch();
        service = new IdentityService(repository, new CredentialProtector(), emailDispatch, Clock.systemUTC());
    }

    @Test
    void registrationVerificationLoginRefreshAndLogoutUseRotatingSecrets() {
        service.register(EMAIL, PASSWORD);

        UserAccount unverified = repository.findUserByEmail("player@example.com").orElseThrow();
        assertThat(unverified.status()).isEqualTo(AccountStatus.UNVERIFIED);
        assertThat(unverified.passwordHash()).isNotEqualTo(PASSWORD).startsWith("$argon2id$");
        assertThatThrownBy(() -> service.login(EMAIL, PASSWORD, "device-a"))
                .isInstanceOf(ApiException.class);

        service.verifyEmail(EMAIL, emailDispatch.codes.get(ChallengePurpose.VERIFY_EMAIL));
        AuthTokens initial = service.login(EMAIL, PASSWORD, "device-a");
        AuthSession stored = repository.findSessionById(initial.sessionId()).orElseThrow();
        assertThat(stored.refreshTokenHash()).doesNotContain(initial.refreshToken());
        assertThat(stored.accessTokenHash()).doesNotContain(initial.accessToken());

        AuthTokens rotated = service.refresh(initial.refreshToken());
        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(repository.findSessionById(initial.sessionId()).orElseThrow().generation()).isEqualTo(2);
        assertThatThrownBy(() -> service.refresh(initial.refreshToken()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("REFRESH_TOKEN_INVALID");

        AuthTokens afterReplay = service.login(EMAIL, PASSWORD, "device-a");
        service.logout(afterReplay.accessToken());
        service.logout(afterReplay.accessToken());
        assertThat(repository.findSessionById(afterReplay.sessionId()).orElseThrow().revokedAt()).isNotNull();
        assertThatThrownBy(() -> service.refresh(afterReplay.refreshToken()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void accessAuthenticationChecksSessionSecretExpiryAndAccountStatus() {
        service.register(EMAIL, PASSWORD);
        service.verifyEmail(EMAIL, emailDispatch.codes.get(ChallengePurpose.VERIFY_EMAIL));
        AuthTokens tokens = service.login(EMAIL, PASSWORD, "device-a");

        assertThat(service.authenticate("Bearer " + tokens.accessToken()).emailNormalized())
                .isEqualTo("player@example.com");
        assertThatThrownBy(() -> service.authenticate("Bearer " + tokens.sessionId() + ".wrong-secret"))
                .isInstanceOf(ApiException.class);
        service.logout(tokens.accessToken());
        assertThatThrownBy(() -> service.authenticate("Bearer " + tokens.accessToken()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void verificationChallengeIsSingleUseAndFailedAttemptsAreCounted() {
        service.register(EMAIL, PASSWORD);
        String code = emailDispatch.codes.get(ChallengePurpose.VERIFY_EMAIL);
        UserAccount user = repository.findUserByEmail("player@example.com").orElseThrow();

        assertThatThrownBy(() -> service.verifyEmail(EMAIL, "000000"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("CHALLENGE_INVALID");
        assertThat(repository.findLatestChallenge(user.id(), ChallengePurpose.VERIFY_EMAIL).orElseThrow().attempts())
                .isEqualTo(1);

        service.verifyEmail(EMAIL, code);
        assertThatThrownBy(() -> service.verifyEmail(EMAIL, code)).isInstanceOf(ApiException.class);
        assertThat(service.requireActive(user.id()).status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void passwordResetRevokesExistingSessionsAndChangesPassword() {
        service.register(EMAIL, PASSWORD);
        service.verifyEmail(EMAIL, emailDispatch.codes.get(ChallengePurpose.VERIFY_EMAIL));
        AuthTokens initial = service.login(EMAIL, PASSWORD, "device-a");

        service.forgotPassword(EMAIL);
        service.resetPassword(EMAIL, emailDispatch.codes.get(ChallengePurpose.PASSWORD_RESET), "a-new-strong-password");

        assertThat(repository.findSessionById(initial.sessionId()).orElseThrow().revokedAt()).isNotNull();
        assertThatThrownBy(() -> service.refresh(initial.refreshToken())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.login(EMAIL, PASSWORD, "device-a")).isInstanceOf(ApiException.class);
        assertThat(service.login(EMAIL, "a-new-strong-password", "device-b").sessionId()).isNotBlank();
    }

    @Test
    void unavailableEmailFailsWithoutCreatingAccountOrPretendingDelivery() {
        emailDispatch.available = false;

        assertThatThrownBy(() -> service.register(EMAIL, PASSWORD))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("EMAIL_DELIVERY_UNAVAILABLE");
        assertThat(repository.users).isEmpty();
        assertThat(emailDispatch.sent).isZero();
    }

    @Test
    void forgotPasswordDoesNotRevealUnknownAccounts() {
        service.forgotPassword("unknown@example.com");

        assertThat(emailDispatch.sent).isZero();
        assertThat(repository.challenges).isEmpty();
    }

    private static class FakeEmailDispatch implements EmailDispatchPort {
        private final Map<ChallengePurpose, String> codes = new EnumMap<>(ChallengePurpose.class);
        private boolean available = true;
        private int sent;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public void sendChallenge(String email, ChallengePurpose purpose, String code) {
            if (!available) {
                throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "EMAIL_DELIVERY_UNAVAILABLE", "邮件服务尚未接入", true);
            }
            codes.put(purpose, code);
            sent++;
        }
    }

    private static class InMemoryIdentityRepository implements IdentityRepository {
        private final Map<String, UserAccount> users = new HashMap<>();
        private final Map<String, EmailChallenge> challenges = new HashMap<>();
        private final Map<String, AuthSession> sessions = new HashMap<>();

        @Override
        public Optional<UserAccount> findUserByEmail(String emailNormalized) {
            return users.values().stream().filter(user -> user.emailNormalized().equals(emailNormalized)).findFirst();
        }

        @Override
        public Optional<UserAccount> findUserById(String userId) {
            return Optional.ofNullable(users.get(userId));
        }

        @Override
        public void insertUser(UserAccount user) {
            users.put(user.id(), user);
        }

        @Override
        public void updateUser(UserAccount user) {
            users.put(user.id(), user);
        }

        @Override
        public Optional<EmailChallenge> findLatestChallenge(String userId, ChallengePurpose purpose) {
            return challenges.values().stream()
                    .filter(item -> item.userId().equals(userId) && item.purpose() == purpose)
                    .max(Comparator.comparing(EmailChallenge::createdAt));
        }

        @Override
        public void insertChallenge(EmailChallenge challenge) {
            challenges.put(challenge.id(), challenge);
        }

        @Override
        public void updateChallenge(EmailChallenge challenge) {
            challenges.put(challenge.id(), challenge);
        }

        @Override
        public Optional<AuthSession> findSessionById(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public Optional<AuthSession> findSessionByAccessTokenHash(String accessTokenHash) {
            return sessions.values().stream().filter(item -> item.accessTokenHash().equals(accessTokenHash)).findFirst();
        }

        @Override
        public void insertSession(AuthSession session) {
            sessions.put(session.id(), session);
        }

        @Override
        public void updateSession(AuthSession session) {
            sessions.put(session.id(), session);
        }

        @Override
        public void revokeAllSessions(String userId, Instant revokedAt) {
            sessions.replaceAll((id, session) -> {
                if (!session.userId().equals(userId) || session.revokedAt() != null) return session;
                return new AuthSession(session.id(), session.userId(), session.deviceId(), session.refreshTokenHash(),
                        session.accessTokenHash(), session.expiresAt(), session.accessExpiresAt(), revokedAt,
                        session.generation());
            });
        }
    }
}
