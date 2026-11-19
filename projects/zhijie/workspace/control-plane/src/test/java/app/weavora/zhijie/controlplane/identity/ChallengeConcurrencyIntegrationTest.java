package app.weavora.zhijie.controlplane.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.common.ApiException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ChallengeConcurrencyIntegrationTest {
    @Test void emailVerificationChallengeCannotBeConsumedByTwoConcurrentRequests() throws Exception {
        concurrentConsumption(ChallengePurpose.VERIFY_EMAIL);
    }

    @Test void passwordResetChallengeCannotBeConsumedByTwoConcurrentRequests() throws Exception {
        concurrentConsumption(ChallengePurpose.PASSWORD_RESET);
    }

    private void concurrentConsumption(ChallengePurpose purpose) throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = application(postgres);
             var workers = Executors.newFixedThreadPool(2)) {
            var identity = context.getBean(IdentityService.class);
            var repository = context.getBean(IdentityRepository.class);
            var protector = context.getBean(CredentialProtector.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            var transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            String user = UUID.randomUUID().toString();
            String email = user + "@example.test";
            repository.insertUser(new UserAccount(user, email, purpose == ChallengePurpose.VERIFY_EMAIL
                    ? AccountStatus.UNVERIFIED : AccountStatus.ACTIVE, protector.hashPassword("original-password-123"), Instant.now()));
            repository.insertChallenge(new EmailChallenge(UUID.randomUUID().toString(), user, purpose,
                    protector.hashChallenge(user, purpose, "123456"), Instant.now().plusSeconds(60), 0, null, Instant.now()));
            var held = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var blocker = new AtomicInteger();
            var first = workers.submit(() -> transactions.executeWithoutResult(status -> {
                blocker.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                consume(identity, email, purpose, "first-password-123");
                held.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }));
            Future<String> second = null;
            try {
                assertThat(held.await(10, TimeUnit.SECONDS)).as("First consumption holds uncommitted row").isTrue();
                second = workers.submit(() -> {
                    try { consume(identity, email, purpose, "second-password-123"); return "ACCEPTED"; }
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
                assertThat(blocked).as("Second request really waits on PostgreSQL row lock").isTrue();
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS)).as("One-time challenge under concurrency").isEqualTo("CHALLENGE_INVALID");
            assertThat(repository.findLatestChallenge(user, purpose).orElseThrow().consumedAt()).isNotNull();
            assertThat(repository.findUserById(user).orElseThrow().status()).isEqualTo(AccountStatus.ACTIVE);
            if (purpose == ChallengePurpose.PASSWORD_RESET) {
                assertThat(identity.login(email, "first-password-123", "after-reset").userId()).isEqualTo(user);
                assertThatThrownBy(() -> identity.login(email, "second-password-123", "stale-reset")).isInstanceOf(ApiException.class);
            }
        }
    }

    @Test void invalidPasswordResetAttemptsPersistAndExhaustChallenge() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = application(postgres)) {
            var identity = context.getBean(IdentityService.class);
            var repository = context.getBean(IdentityRepository.class);
            var protector = context.getBean(CredentialProtector.class);
            String user = UUID.randomUUID().toString();
            String email = user + "@example.test";
            repository.insertUser(new UserAccount(user, email, AccountStatus.ACTIVE,
                    protector.hashPassword("original-password-123"), Instant.now()));
            repository.insertChallenge(new EmailChallenge(UUID.randomUUID().toString(), user, ChallengePurpose.PASSWORD_RESET,
                    protector.hashChallenge(user, ChallengePurpose.PASSWORD_RESET, "123456"), Instant.now().plusSeconds(60), 0, null, Instant.now()));
            for (int attempt = 1; attempt <= 5; attempt++) {
                assertThatThrownBy(() -> identity.resetPassword(email, "000000", "replacement-password-123"))
                        .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CHALLENGE_INVALID");
                assertThat(repository.findLatestChallenge(user, ChallengePurpose.PASSWORD_RESET).orElseThrow().attempts()).isEqualTo(attempt);
            }
            assertThatThrownBy(() -> identity.resetPassword(email, "123456", "replacement-password-123"))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CHALLENGE_INVALID");
            assertThat(identity.login(email, "original-password-123", "unchanged").userId()).isEqualTo(user);
        }
    }

    private void consume(IdentityService identity, String email, ChallengePurpose purpose, String password) {
        if (purpose == ChallengePurpose.VERIFY_EMAIL) identity.verifyEmail(email, "123456");
        else identity.resetPassword(email, "123456", password);
    }

    private ConfigurableApplicationContext application(EmbeddedPostgres postgres) {
        var app = new SpringApplication(ControlPlaneApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run("--zhijie.scheduling.enabled=false", "--zhijie.mail.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=");
    }
}
