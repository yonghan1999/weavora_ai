package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.identity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

class SyncKeepAliveIntegrationTest {
    @Test void isolatedWebsocketStillOpensWhenAnHttpConnectionReachesItsKeepAliveLimit() throws Exception {
        var working = Path.of(System.getProperty("user.dir"));
        var workspace = Files.isRegularFile(working.resolve("control-plane/pom.xml")) ? working : working.getParent();
        var directory = Files.createTempDirectory(Files.createDirectories(workspace.resolve("target")), "sync-keepalive-");
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var credentials = context.getBean(CredentialProtector.class);
            var identities = context.getBean(IdentityRepository.class);
            String user = UUID.randomUUID().toString();
            String session = UUID.randomUUID().toString();
            String secret = credentials.randomSecret();
            identities.insertUser(new UserAccount(user, "keepalive@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
            identities.insertSession(new AuthSession(session, user, "keepalive", "unused", credentials.hashToken(secret),
                    Instant.now().plusSeconds(120), Instant.now().plusSeconds(120), null, 1L));
            var process = new ProcessBuilder("node", "scripts/test-sync-keepalive.mjs").directory(workspace.toFile())
                    .redirectErrorStream(true).redirectOutput(directory.resolve("probe.log").toFile()).start();
            try {
                var configuration = Map.of("port", ((ServletWebServerApplicationContext)context).getWebServer().getPort(),
                        "accessToken", session + "." + secret, "cursor", context.getBean(OutboxRepository.class).latestSequence());
                try (var input = process.getOutputStream()) {
                    input.write(context.getBean(ObjectMapper.class).writeValueAsBytes(configuration));
                    input.write('\n');
                }
                assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("keep-alive probe deadline: %s", directory).isTrue();
                assertThat(process.exitValue()).as("keep-alive probe: %s", directory).isZero();
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        }
    }
}
