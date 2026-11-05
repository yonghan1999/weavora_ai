package app.weavora.zhijie.controlplane.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogSanitizerTest {
    @Test
    void logsRedactCredentialsTokensAndPasswordHashes() {
        String input = "password=correct-horse token: abc123 Authorization=Bearer secret-token " +
                "hash=$argon2id$v=19$m=16384,t=2,p=1$abc$def";
        String safe = LogSanitizer.sanitize(input);
        assertThat(safe).doesNotContain("correct-horse", "abc123", "secret-token", "$argon2id$");
        assertThat(safe).contains("[REDACTED]", "[REDACTED_PASSWORD_HASH]");
    }
}
