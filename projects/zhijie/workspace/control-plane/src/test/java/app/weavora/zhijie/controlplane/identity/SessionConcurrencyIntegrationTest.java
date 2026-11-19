package app.weavora.zhijie.controlplane.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.common.ApiException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SessionConcurrencyIntegrationTest {
    @Test void pendingRefreshCannotOverwriteCommittedLogoutRotationOrPasswordReset() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identity = context.getBean(IdentityService.class);
            var repository = context.getBean(IdentityRepository.class);
            var protector = context.getBean(CredentialProtector.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            var transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            try (var workers = Executors.newFixedThreadPool(2)) {
                for (String action : new String[]{"logout", "rotate", "password-reset", "logout-after-rotate"}) {
                    String user = UUID.randomUUID().toString();
                    String email = user + "@example.test";
                    repository.insertUser(new UserAccount(user, email, AccountStatus.ACTIVE,
                            protector.hashPassword("concurrent-password-123"), Instant.now()));
                    var original = identity.login(email, "concurrent-password-123", "concurrent-device");
                    String otherUser = UUID.randomUUID().toString();
                    repository.insertUser(new UserAccount(otherUser, otherUser + "@example.test", AccountStatus.ACTIVE,
                            protector.hashPassword("unrelated-password-123"), Instant.now()));
                    var unrelated = identity.login(otherUser + "@example.test", "unrelated-password-123", "other-device");
                    if (action.equals("password-reset")) {
                        repository.insertChallenge(new EmailChallenge(UUID.randomUUID().toString(), user,
                                ChallengePurpose.PASSWORD_RESET, protector.hashChallenge(user, ChallengePurpose.PASSWORD_RESET, "123456"),
                                Instant.now().plusSeconds(60), 0, null, Instant.now()));
                    }
                    var held = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    var blocker = new java.util.concurrent.atomic.AtomicInteger();
                    Future<AuthTokens> first = workers.submit(() -> transactions.execute(status -> {
                        blocker.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        AuthTokens rotated = null;
                        if (action.equals("rotate") || action.equals("logout-after-rotate")) rotated = identity.refresh(original.refreshToken());
                        else if (action.equals("logout")) identity.logout(original.accessToken());
                        else identity.resetPassword(email, "123456", "replacement-password-123");
                        held.countDown();
                        try {
                            assertThat(release.await(10, TimeUnit.SECONDS)).as("Release transaction deadline").isTrue();
                        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                        return rotated;
                    }));
                    Future<String> second = null;
                    try {
                        assertThat(held.await(10, TimeUnit.SECONDS)).as("First transaction acquired session row").isTrue();
                        second = workers.submit(() -> {
                            try {
                                if (action.equals("logout-after-rotate")) identity.logout(original.accessToken());
                                else identity.refresh(original.refreshToken());
                                return "ACCEPTED";
                            }
                            catch (ApiException error) { return error.code(); }
                        });
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        boolean blocked = false;
                        while (System.nanoTime() < deadline) {
                            blocked = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                                    Integer.class, blocker.get()) > 0;
                            if (blocked) break;
                            Thread.sleep(10);
                        }
                        assertThat(blocked).as("Concurrent request actually waits on PostgreSQL session row").isTrue();
                        // 其他账号无需等待被测会话事务提交。
                        unrelated = identity.refresh(unrelated.refreshToken());
                        assertThat(identity.authenticate("Bearer " + unrelated.accessToken()).id()).isEqualTo(otherUser);
                    } finally { release.countDown(); }
                    AuthTokens rotated = first.get(5, TimeUnit.SECONDS);
                    boolean staleLogout = action.equals("logout-after-rotate");
                    assertThat(second.get(5, TimeUnit.SECONDS)).as("Stale operation after %s", action)
                            .isEqualTo(staleLogout ? "ACCESS_TOKEN_INVALID" : "REFRESH_TOKEN_INVALID");
                    var current = repository.findSessionById(original.sessionId()).orElseThrow();
                    if (staleLogout) assertThat(current.revokedAt()).as("Stale logout preserves rotated session").isNull();
                    else assertThat(current.revokedAt()).as("No session resurrection after %s", action).isNotNull();
                    assertThat(current.generation()).isEqualTo(rotated != null ? 2 : 1);
                    assertThatThrownBy(() -> identity.authenticate("Bearer " + original.accessToken())).isInstanceOf(ApiException.class);
                    if (rotated != null) {
                        var issued = rotated;
                        if (staleLogout) assertThat(identity.authenticate("Bearer " + issued.accessToken()).id()).isEqualTo(user);
                        else assertThatThrownBy(() -> identity.authenticate("Bearer " + issued.accessToken())).isInstanceOf(ApiException.class);
                    }
                    assertThat(identity.authenticate("Bearer " + unrelated.accessToken()).id()).isEqualTo(otherUser);
                }
            }
        }
    }
}
