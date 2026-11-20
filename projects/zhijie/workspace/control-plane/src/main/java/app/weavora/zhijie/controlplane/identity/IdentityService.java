package app.weavora.zhijie.controlplane.identity;

import app.weavora.zhijie.controlplane.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    static final Duration CHALLENGE_TTL = Duration.ofMinutes(15);
    static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    static final Duration REFRESH_TTL = Duration.ofDays(30);
    static final int MAX_CHALLENGE_ATTEMPTS = 5;

    private final IdentityRepository repository;
    private final CredentialProtector credentials;
    private final EmailDispatchPort emailDispatch;
    private final Clock clock;

    public IdentityService(
            IdentityRepository repository,
            CredentialProtector credentials,
            EmailDispatchPort emailDispatch,
            Clock clock
    ) {
        this.repository = repository;
        this.credentials = credentials;
        this.emailDispatch = emailDispatch;
        this.clock = clock;
    }

    @Transactional
    public void register(String email, String password) {
        requireEmailAvailable();
        if (password == null || password.length() < 12) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEAK_PASSWORD", "密码至少需要 12 个字符", false);
        }
        String normalized = normalizeEmail(email);
        repository.findUserByEmail(normalized).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS", "账号已注册", false);
        });
        Instant now = clock.instant();
        UserAccount user = new UserAccount(
                UUID.randomUUID().toString(), normalized, AccountStatus.UNVERIFIED,
                credentials.hashPassword(password), now
        );
        repository.insertUser(user);
        issueChallenge(user, ChallengePurpose.VERIFY_EMAIL);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void verifyEmail(String email, String code) {
        UserAccount user = repository.findUserByEmail(normalizeEmail(email))
                .orElseThrow(this::invalidChallenge);
        EmailChallenge challenge = validateAndConsumeChallenge(user, ChallengePurpose.VERIFY_EMAIL, code);
        repository.updateChallenge(challenge);
        if (user.status() == AccountStatus.UNVERIFIED) {
            repository.updateUser(new UserAccount(
                    user.id(), user.emailNormalized(), AccountStatus.ACTIVE, user.passwordHash(), user.createdAt()
            ));
        }
    }

    @Transactional
    public AuthTokens login(String email, String password, String deviceId) {
        UserAccount user = repository.findUserByEmail(normalizeEmail(email))
                .orElseThrow(this::invalidCredentials);
        if (user.status() != AccountStatus.ACTIVE || !credentials.matchesPassword(password, user.passwordHash())) {
            throw invalidCredentials();
        }
        return createSession(user, deviceId);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthTokens refresh(String refreshToken) {
        TokenParts parts = parseToken(refreshToken, "REFRESH_TOKEN_INVALID");
        // 锁定后再校验秘密与撤销状态，避免并发刷新或退出被旧快照覆盖。
        AuthSession session = repository.findSessionForUpdate(parts.sessionId())
                .orElseThrow(this::invalidRefreshToken);
        Instant now = clock.instant();
        if (session.revokedAt() != null || now.isAfter(session.expiresAt())) {
            throw invalidRefreshToken();
        }
        if (!credentials.hashToken(parts.secret()).equals(session.refreshTokenHash())) {
            AuthSession revoked = revoke(session, now);
            repository.updateSession(revoked);
            throw invalidRefreshToken();
        }
        requireActive(session.userId());
        TokenMaterial material = newTokenMaterial(session.id());
        AuthSession rotated = new AuthSession(
                session.id(), session.userId(), session.deviceId(), material.refreshHash(), material.accessHash(),
                now.plus(REFRESH_TTL), now.plus(ACCESS_TTL), null, session.generation() + 1
        );
        repository.updateSession(rotated);
        return material.tokens(rotated);
    }

    @Transactional
    public void logout(String accessToken) {
        TokenParts parts = parseToken(accessToken, "ACCESS_TOKEN_INVALID");
        AuthSession session = repository.findSessionForUpdate(parts.sessionId())
                .orElseThrow(this::invalidAccessToken);
        if (!credentials.hashToken(parts.secret()).equals(session.accessTokenHash())) {
            throw invalidAccessToken();
        }
        if (session.revokedAt() == null) {
            repository.updateSession(revoke(session, clock.instant()));
        }
    }

    @Transactional
    public void forgotPassword(String email) {
        requireEmailAvailable();
        repository.findUserByEmail(normalizeEmail(email))
                .filter(user -> user.status() == AccountStatus.ACTIVE)
                .ifPresent(user -> issueChallenge(user, ChallengePurpose.PASSWORD_RESET));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void resetPassword(String email, String code, String newPassword) {
        if (newPassword == null || newPassword.length() < 12) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEAK_PASSWORD", "密码至少需要 12 个字符", false);
        }
        UserAccount user = repository.findUserByEmail(normalizeEmail(email))
                .orElseThrow(this::invalidChallenge);
        EmailChallenge challenge = validateAndConsumeChallenge(user, ChallengePurpose.PASSWORD_RESET, code);
        Instant now = clock.instant();
        repository.updateChallenge(challenge);
        repository.updateUser(new UserAccount(
                user.id(), user.emailNormalized(), user.status(), credentials.hashPassword(newPassword), user.createdAt()
        ));
        repository.revokeAllSessions(user.id(), now);
    }

    public UserAccount requireActive(String userId) {
        return repository.findUserById(userId)
                .filter(user -> user.status() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "当前账号没有已验证的有效资格", false
                ));
    }

    public UserAccount authenticate(String authorization) {
        return requireActive(authenticateSession(authorization).userId());
    }

    public AuthSession authenticateSession(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw invalidAccessToken();
        }
        TokenParts parts = parseToken(authorization.substring(7), "ACCESS_TOKEN_INVALID");
        AuthSession session = repository.findSessionById(parts.sessionId()).orElseThrow(this::invalidAccessToken);
        Instant now = clock.instant();
        if (session.revokedAt() != null || !now.isBefore(session.accessExpiresAt())
                || !credentials.hashToken(parts.secret()).equals(session.accessTokenHash())) {
            throw invalidAccessToken();
        }
        requireActive(session.userId());
        return session;
    }

    public void requireSession(String sessionId, long generation, String userId) {
        AuthSession current = repository.findSessionById(sessionId).orElseThrow(this::invalidAccessToken);
        if (current.revokedAt() != null || current.generation() != generation || !current.userId().equals(userId)
                || !clock.instant().isBefore(current.accessExpiresAt())) throw invalidAccessToken();
        requireActive(current.userId());
    }

    public void requireActivitySession(String sessionId, String userId, String deviceId) {
        if (sessionId == null) throw invalidAccessToken();
        repository.findSessionById(sessionId)
                .filter(session -> session.allowsActivity(userId, deviceId, clock.instant()))
                .orElseThrow(this::invalidAccessToken);
        requireActive(userId);
    }

    public void lockSession(AuthSession expected) {
        AuthSession current = repository.findSessionForUpdate(expected.id()).orElseThrow(this::invalidAccessToken);
        if (!current.allowsActivity(expected.userId(), expected.deviceId(), clock.instant())
                || !current.generation().equals(expected.generation()) || !clock.instant().isBefore(current.accessExpiresAt())) {
            throw invalidAccessToken();
        }
        requireActive(current.userId());
    }

    private AuthTokens createSession(UserAccount user, String deviceId) {
        String sessionId = UUID.randomUUID().toString();
        TokenMaterial material = newTokenMaterial(sessionId);
        Instant now = clock.instant();
        AuthSession session = new AuthSession(
                sessionId, user.id(), deviceId, material.refreshHash(), material.accessHash(),
                now.plus(REFRESH_TTL), now.plus(ACCESS_TTL), null, 1L
        );
        repository.insertSession(session);
        return material.tokens(session);
    }

    private TokenMaterial newTokenMaterial(String sessionId) {
        String accessSecret = credentials.randomSecret();
        String refreshSecret = credentials.randomSecret();
        return new TokenMaterial(
                sessionId + "." + accessSecret,
                sessionId + "." + refreshSecret,
                credentials.hashToken(accessSecret),
                credentials.hashToken(refreshSecret)
        );
    }

    private void issueChallenge(UserAccount user, ChallengePurpose purpose) {
        String code = credentials.randomCode();
        Instant now = clock.instant();
        EmailChallenge challenge = new EmailChallenge(
                UUID.randomUUID().toString(), user.id(), purpose,
                credentials.hashChallenge(user.id(), purpose, code), now.plus(CHALLENGE_TTL), 0, null, now
        );
        repository.insertChallenge(challenge);
        emailDispatch.sendChallenge(user.emailNormalized(), purpose, code);
    }

    private EmailChallenge validateAndConsumeChallenge(UserAccount user, ChallengePurpose purpose, String code) {
        // 消费与错误计数在同一验证码行锁下校验，防止并发重放和次数丢失。
        EmailChallenge challenge = repository.findLatestChallengeForUpdate(user.id(), purpose).orElseThrow(this::invalidChallenge);
        Instant now = clock.instant();
        if (challenge.consumedAt() != null || now.isAfter(challenge.expiresAt())
                || challenge.attempts() >= MAX_CHALLENGE_ATTEMPTS) {
            throw invalidChallenge();
        }
        if (!credentials.hashChallenge(user.id(), purpose, code).equals(challenge.codeHash())) {
            repository.updateChallenge(new EmailChallenge(
                    challenge.id(), challenge.userId(), challenge.purpose(), challenge.codeHash(), challenge.expiresAt(),
                    challenge.attempts() + 1, challenge.consumedAt(), challenge.createdAt()
            ));
            throw invalidChallenge();
        }
        return new EmailChallenge(
                challenge.id(), challenge.userId(), challenge.purpose(), challenge.codeHash(), challenge.expiresAt(),
                challenge.attempts(), now, challenge.createdAt()
        );
    }

    private void requireEmailAvailable() {
        if (!emailDispatch.isAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE", "邮件服务尚未接入", true);
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "邮箱不能为空", false);
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private TokenParts parseToken(String token, String code) {
        if (token == null || token.indexOf('.') <= 0 || token.indexOf('.') == token.length() - 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, code, "凭据无效", false);
        }
        int separator = token.indexOf('.');
        return new TokenParts(token.substring(0, separator), token.substring(separator + 1));
    }

    private AuthSession revoke(AuthSession session, Instant now) {
        return new AuthSession(
                session.id(), session.userId(), session.deviceId(), session.refreshTokenHash(), session.accessTokenHash(),
                session.expiresAt(), session.accessExpiresAt(), now, session.generation()
        );
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "邮箱或密码错误，或账号尚未验证", false);
    }

    private ApiException invalidChallenge() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHALLENGE_INVALID", "验证码无效、已使用或已过期", false);
    }

    private ApiException invalidRefreshToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID", "刷新凭据无效或已撤销", false);
    }

    private ApiException invalidAccessToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID", "访问凭据无效或已撤销", false);
    }

    private record TokenParts(String sessionId, String secret) {
    }

    private record TokenMaterial(String accessToken, String refreshToken, String accessHash, String refreshHash) {
        AuthTokens tokens(AuthSession session) {
            return new AuthTokens(
                    session.id(), session.userId(), accessToken, refreshToken, session.accessExpiresAt(), session.expiresAt()
            );
        }
    }
}
