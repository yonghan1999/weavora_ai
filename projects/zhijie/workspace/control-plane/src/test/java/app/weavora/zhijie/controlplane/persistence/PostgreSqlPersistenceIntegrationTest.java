package app.weavora.zhijie.controlplane.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.audit.AuditService;
import app.weavora.zhijie.controlplane.group.Group;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.AuthTokens;
import app.weavora.zhijie.controlplane.identity.ChallengePurpose;
import app.weavora.zhijie.controlplane.identity.EmailDispatchPort;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import app.weavora.zhijie.controlplane.message.MessageService;
import app.weavora.zhijie.controlplane.message.MessageView;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

class PostgreSqlPersistenceIntegrationTest {
    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.start();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test
    void flywayMigrationsAndBusinessDataSurviveApplicationRestart() {
        String email = "restart@example.com";
        String groupId;
        String messageId;
        try (ConfigurableApplicationContext context = newContext()) {
            IdentityService identity = context.getBean(IdentityService.class);
            TestEmailDispatch mail = context.getBean(TestEmailDispatch.class);
            identity.register(email, "restart-test-password");
            identity.verifyEmail(email, mail.codes.get(ChallengePurpose.VERIFY_EMAIL));
            AuthTokens tokens = identity.login(email, "restart-test-password", "device-a");
            assertThat(identity.authenticate("Bearer " + tokens.accessToken()).emailNormalized()).isEqualTo(email);

            String userId = identity.authenticate("Bearer " + tokens.accessToken()).id();
            Group group = context.getBean(GroupService.class).createGroup(userId, "持久小队", null, "重启后保持");
            groupId = group.id();
            MessageView message = context.getBean(MessageService.class).post(userId, groupId, "持久消息");
            messageId = message.id();
            context.getBean(AuditService.class).record(groupId, userId, "GROUP", groupId, "GROUP_CREATE", "SUCCESS", "restart-test");
        }

        try (ConfigurableApplicationContext restarted = newContext()) {
            AuthTokens tokens = restarted.getBean(IdentityService.class).login(email, "restart-test-password", "device-b");
            String userId = restarted.getBean(IdentityService.class).authenticate("Bearer " + tokens.accessToken()).id();
            Group group = restarted.getBean(GroupService.class).getGroup(userId, groupId);
            assertThat(group.name()).isEqualTo("持久小队");
            assertThat(restarted.getBean(MessageService.class).list(userId, groupId, null, 50))
                    .extracting(MessageView::id).contains(messageId);
            assertThat(restarted.getBean(AuditService.class).list(userId, groupId, null, 50))
                    .extracting("action").contains("GROUP_CREATE");
        }
    }

    private ConfigurableApplicationContext newContext() {
        SpringApplication application = new SpringApplication(ControlPlaneApplication.class, TestMailConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres",
                "--spring.datasource.password="
        );
    }

    @Configuration(proxyBeanMethods = false)
    static class TestMailConfiguration {
        @Bean
        @Primary
        TestEmailDispatch testEmailDispatch() {
            return new TestEmailDispatch();
        }
    }

    static class TestEmailDispatch implements EmailDispatchPort {
        private final Map<ChallengePurpose, String> codes = new EnumMap<>(ChallengePurpose.class);
        public boolean isAvailable() { return true; }
        public void sendChallenge(String email, ChallengePurpose purpose, String code) { codes.put(purpose, code); }
    }
}
