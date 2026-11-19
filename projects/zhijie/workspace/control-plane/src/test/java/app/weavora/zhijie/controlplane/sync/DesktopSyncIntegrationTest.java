package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;
import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.integration.CargoTestClient;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.message.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

class DesktopSyncIntegrationTest {
    @Test void actualRustClientReadsLiveAndMissedMessagesThenReconnectsWithRotatedToken() throws Exception {
        var workspace = Path.of(System.getProperty("user.dir")).getParent();
        var executable = CargoTestClient.build(workspace, "example", "sync-probe");
        try (var postgres = EmbeddedPostgres.builder().setPGStartupWait(java.time.Duration.ofSeconds(60)).start();
                var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identity = context.getBean(IdentityService.class);
            String user = UUID.randomUUID().toString();
            context.getBean(IdentityRepository.class).insertUser(new UserAccount(user, "rust-sync@example.test", AccountStatus.ACTIVE,
                    context.getBean(CredentialProtector.class).hashPassword("rust-sync-password-123"), Instant.now()));
            var tokens = identity.login("rust-sync@example.test", "rust-sync-password-123", "rust-sync");
            var group = context.getBean(GroupService.class).createGroup(user, "真实原生同步", null, "");
            var messages = context.getBean(MessageService.class);
            var json = context.getBean(ObjectMapper.class);
            var log = Files.createTempFile(workspace.resolve("target"), "desktop-sync-rust-", ".log");
            var process = new ProcessBuilder(executable.toString())
                    .directory(workspace.toFile()).redirectError(log.toFile()).start();
            try (var output = process.getOutputStream(); var input = process.inputReader(StandardCharsets.UTF_8)) {
                int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
                var http = HttpClient.newHttpClient();
                var endpoint = URI.create("http://127.0.0.1:" + port + "/api/v1/sync/cursor");
                assertThat(http.send(HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(400);
                assertThat(http.send(HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer invalid").GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
                send(output, json, Map.of("base", "http://127.0.0.1:" + port, "token", tokens.accessToken()));
                expect(input, "SYNC_READY");
                String first = messages.post(user, group.id(), "live").id();
                send(output, json, Map.of("message", first));
                expect(input, "SYNC_OFFLINE");
                String missed = messages.post(user, group.id(), "offline").id();
                send(output, json, Map.of("message", missed));
                expect(input, "SYNC_CATCHUP_OK");
                var fresh = identity.refresh(tokens.refreshToken());
                String next = messages.post(user, group.id(), "rotated").id();
                send(output, json, Map.of("message", next, "token", fresh.accessToken()));
                expect(input, "SYNC_ROTATED_OK");
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).as("Rust diagnostics: %s", log).isZero();
                assertThat(Files.readString(log)).doesNotContain(tokens.accessToken(), fresh.accessToken());
            } finally {
                if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            }
        }
    }

    private void send(OutputStream output, ObjectMapper json, Object value) throws IOException {
        output.write(json.writeValueAsBytes(value)); output.write('\n'); output.flush();
    }
    private void expect(BufferedReader input, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!input.ready() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(input.ready()).as("Rust client signal deadline").isTrue();
        assertThat(input.readLine()).isEqualTo(expected);
    }
}
