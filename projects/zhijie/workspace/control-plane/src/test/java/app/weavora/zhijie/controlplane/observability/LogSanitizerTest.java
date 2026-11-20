package app.weavora.zhijie.controlplane.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogSanitizerTest {
    @Test
    void productionConsoleDoesNotAppendRawExceptionDetails() throws Exception {
        var context = new LoggerContext();
        try {
            var configuration = new JoranConfigurator();
            configuration.setContext(context);
            configuration.doConfigure(getClass().getResource("/logback-spring.xml"));
            Logger logger = context.getLogger(Logger.ROOT_LOGGER_NAME);
            var console = (ConsoleAppender<ILoggingEvent>) logger.getAppender("CONSOLE");
            var cause = new IllegalArgumentException("fixture-cause-value");
            var failure = new IllegalStateException("fixture-outer-value", cause);
            failure.addSuppressed(new Exception("fixture-suppressed-value"));
            var event = new LoggingEvent(getClass().getName(), logger, Level.ERROR, "operation failed token={}",
                    failure, new Object[]{"fixture-message-value"});
            event.setMDCPropertyMap(Map.of("correlation_id", "request-fixture"));
            String line = new String(console.getEncoder().encode(event), StandardCharsets.UTF_8);
            assertThat(line).contains("request-fixture", "operation failed token=[REDACTED]", "IllegalStateException")
                    .doesNotContain("fixture-message-value", "fixture-outer-value", "fixture-cause-value", "fixture-suppressed-value");
            var payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "networkTicket", "fixture-network\"tail", "newPassword", "fixture-password\\tail",
                    "X-Relay-Key", "fixture-management", "errorCode", "NETWORK_GRANT_INVALID"));
            var structured = new LoggingEvent(getClass().getName(), logger, Level.WARN, "operation result {}",
                    failure, new Object[]{payload});
            structured.setMDCPropertyMap(Map.of("correlation_id", "request-fixture"));
            String structuredLine = new String(console.getEncoder().encode(structured), StandardCharsets.UTF_8);
            assertThat(structuredLine).contains("request-fixture", "NETWORK_GRANT_INVALID", "[REDACTED]", "IllegalStateException")
                    .doesNotContain("fixture-network", "fixture-password", "fixture-management", "tail",
                            "fixture-outer-value", "fixture-cause-value", "fixture-suppressed-value");
        } finally {
            context.stop();
        }
    }

    @Test
    void logsRedactCredentialsTokensAndPasswordHashes() {
        String input = "password=correct-horse token: abc123 Authorization=Bearer secret-token " +
                "hash=$argon2id$v=19$m=16384,t=2,p=1$abc$def";
        String safe = LogSanitizer.sanitize(input);
        assertThat(safe).doesNotContain("correct-horse", "abc123", "secret-token", "$argon2id$");
        assertThat(safe).contains("[REDACTED]", "[REDACTED_PASSWORD_HASH]");
    }

    @Test
    void structuredFieldsRedactQuotedKeysAndEscapedSecretValues() {
        for (String field : List.of("password", "newPassword", "roomPassword", "accessToken", "refresh_token",
                "networkTicket", "ticket", "private_key", "apiKey", "api-secret", "signalingKey",
                "X-Relay-Key", "verificationCode", "reset_code", "authorization")) {
            String input = "{\"" + field + "\":\"fixture-before\\\"fixture-after\",\"state\":\"connected\"}";
            assertThat(LogSanitizer.sanitize(input)).as("JSON field %s", field)
                    .doesNotContain("fixture-before", "fixture-after").contains("connected", "[REDACTED]");
        }
        assertThat(LogSanitizer.sanitize("{'roomPassword': 'fixture-one\\'fixture-two', 'state': 'active'}"))
                .doesNotContain("fixture-one", "fixture-two").contains("active", "[REDACTED]");
    }

    @Test
    void recordFieldsRedactNetworkAndServiceSecretsWithoutHidingSafeMetadata() {
        String input = "NetworkGrantResponse(networkTicket=fixture-ticket, allowedPaths=[DIRECT]) " +
                "privateKey=fixture-private apiKey:fixture-api signaling_key=fixture-signaling " +
                "X-Relay-Key=fixture-relay verification_code=fixture-code status=FAILED errorCode=NETWORK_GRANT_INVALID";
        assertThat(LogSanitizer.sanitize(input))
                .doesNotContain("fixture-ticket", "fixture-private", "fixture-api", "fixture-signaling", "fixture-relay", "fixture-code")
                .contains("allowedPaths=[DIRECT]", "status=FAILED", "errorCode=NETWORK_GRANT_INVALID");
    }
}
