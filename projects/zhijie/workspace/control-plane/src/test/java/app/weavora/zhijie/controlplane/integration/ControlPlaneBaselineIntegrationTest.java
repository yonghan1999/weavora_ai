package app.weavora.zhijie.controlplane.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class ControlPlaneBaselineIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_CONTROL_BASELINE", matches = "true")
    void observeIdleAndFixedControlTrafficWithoutAccounts() throws Exception {
        String networkInterface = System.getenv("ZHIJIE_TEST_RESOURCE_INTERFACE");
        assertThat(networkInterface).as("explicit local interface required").isNotBlank();
        Path working = Path.of(System.getProperty("user.dir"));
        Path workspace = Files.isDirectory(working.resolve("control-plane/src")) ? working : working.getParent();
        Path output = Files.createTempDirectory(Files.createDirectories(workspace.resolve("target")), "control-baseline-");
        System.out.println("C0 local baseline: " + output);
        try (var postgres = EmbeddedPostgres.start(); var context = (ServletWebServerApplicationContext)
                SpringApplication.run(ControlPlaneApplication.class, "--server.address=127.0.0.1", "--server.port=0",
                        "--zhijie.voice.enabled=false", "--zhijie.mail.enabled=false",
                        "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                        "--spring.datasource.username=postgres", "--spring.datasource.password=" );
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            var sql = context.getBean(JdbcTemplate.class);
            var json = context.getBean(ObjectMapper.class);
            assertThat(sql.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
            var health = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + "/actuator/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            Path observation = output.resolve("host-observation"), stop = output.resolve("sampling.stop");
            var sampler = new ProcessBuilder(System.getenv().getOrDefault("ZHIJIE_TEST_PYTHON", "python"), "-B",
                    "scripts/sample-capacity-resources.py", "--pid", Long.toString(ProcessHandle.current().pid()),
                    "--interface", networkInterface, "--duration", "90", "--stop-file", stop.toString(), "--output", observation.toString())
                    .directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(output.resolve("sampler.log").toFile()).start();
            try (var writer = Files.newBufferedWriter(output.resolve("control.jsonl"))) {
                Path raw = observation.resolve("samples.jsonl");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (sampler.isAlive() && System.nanoTime() < deadline && (!Files.exists(raw) || Files.size(raw) == 0)) Thread.sleep(50);
                assertThat(sampler.isAlive() && Files.exists(raw) && Files.size(raw) > 0).as("sampler ready: %s", output).isTrue();
                long started = System.nanoTime();
                for (int second = 0; second < 60; second++) {
                    long offset = System.nanoTime() - started;
                    assertThat(offset).as("sample %s missed its scheduled second", second).isLessThan(TimeUnit.SECONDS.toNanos(second + 1));
                    int connections = sql.queryForObject("SELECT numbackends FROM pg_stat_database WHERE datname=current_database()", Integer.class);
                    assertThat(connections).isPositive();
                    var sample = new java.util.LinkedHashMap<String, Object>();
                    sample.put("timeUtc", Instant.now().toString());
                    sample.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(offset));
                    sample.put("databaseConnections", connections);
                    sample.put("phase", second < 30 ? "idle" : "fixed-health");
                    if (second >= 30) {
                        long requestStart = System.nanoTime();
                        var response = client.send(health, HttpResponse.BodyHandlers.ofString());
                        sample.put("httpStatus", response.statusCode());
                        sample.put("latencyMs", (System.nanoTime() - requestStart) / 1_000_000.0);
                        assertThat(response.statusCode()).isEqualTo(200);
                        assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("UP");
                    }
                    writer.write(json.writeValueAsString(sample)); writer.newLine(); writer.flush();
                    TimeUnit.NANOSECONDS.sleep(started + TimeUnit.SECONDS.toNanos(second + 1) - System.nanoTime());
                }
                Files.createFile(stop);
                assertThat(sampler.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(sampler.exitValue()).isZero();
                var host = json.readTree(observation.resolve("summary.json").toFile());
                assertThat(host.path("endCondition").asText()).isEqualTo("stop-file");
                assertThat(host.path("measuredDurationSeconds").asDouble()).isGreaterThanOrEqualTo(60);
                assertThat(sql.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
                json.writeValue(output.resolve("summary.json").toFile(), Map.of("scenario", "C0", "accounts", 0,
                        "idleSeconds", 30, "fixedHealthRequests", 30, "databaseSamples", 60,
                        "scope", "local-jvm-and-loopback-control", "acceptance", "unverified_target_server"));
            } finally {
                if (sampler.isAlive()) sampler.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
    }
}
