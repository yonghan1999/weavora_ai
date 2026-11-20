package app.weavora.zhijie.controlplane.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.NetworkProbeRevocation;
import app.weavora.zhijie.controlplane.network.HostileNetworkProbe;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

class SessionNetworkRevocationIntegrationTest {
    private static final List<NetworkProbeRevocation> ACTIONS = List.of(NetworkProbeRevocation.LOGOUT,
            NetworkProbeRevocation.REFRESH_REPLAY, NetworkProbeRevocation.SESSION_EXPIRED);

    @BeforeAll static void build() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("ZHIJIE_TEST_RELAY_MEDIA")));
        var directory = Files.createTempDirectory(Path.of("target"), "session-network-build-");
        for (var arguments : List.of(List.of("cargo", "test", "--locked", "--offline", "-p", "zhijie-helper", "--lib", "--no-run"),
                List.of("cargo", "test", "--locked", "--offline", "-p", "zhijie-relay", "--test", "authority_loss", "--no-run"))) {
            var log = directory.resolve(arguments.contains("zhijie-helper") ? "helper.log" : "relay.log");
            var process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertThat(process.waitFor(180, TimeUnit.SECONDS)).as("Native build: %s", log).isTrue();
                assertThat(process.exitValue()).as("Native build: %s", log).isZero();
            } finally {
                if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            }
        }
    }

    @Test void realDirectCiphertextAndFreshHandshakesStopAfterAuthenticationRevocation() throws Exception {
        try (var environment = start(false)) {
            for (var action : ACTIONS) for (String mode : List.of("online", "offline")) {
                HostileNetworkProbe.run(environment.context(), mode, action);
            }
        }
    }

    @Test void realQuicAuthenticationRevocationSurvivesOnlineAndBlackholedAuthority() throws Exception {
        try (var environment = start(false)) {
            for (var action : ACTIONS) for (String mode : List.of("online", "offline")) {
                RelayAuthorityIntegrationTest.run(environment.context(), environment.validationKey(), environment.revocationKey(),
                        environment.managementPort(), mode, action, false);
            }
        }
    }

    @Test void scheduledSessionCleanupAndOutboxPushCloseRealQuicWithoutClientStop() throws Exception {
        try (var environment = start(true)) {
            for (var action : ACTIONS) {
                RelayAuthorityIntegrationTest.run(environment.context(), environment.validationKey(), environment.revocationKey(),
                        environment.managementPort(), "push-offline", action, true);
            }
        }
    }

    private static Environment start(boolean push) throws Exception {
        int port;
        try (var reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = reservation.getLocalPort(); }
        String validationKey = UUID.randomUUID().toString(), revocationKey = UUID.randomUUID().toString();
        var postgres = EmbeddedPostgres.start();
        try {
            var context = (ServletWebServerApplicationContext)new SpringApplication(ControlPlaneApplication.class).run(
                    "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=true",
                    "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                    "--spring.datasource.username=postgres", "--spring.datasource.password=",
                    "--zhijie.relay.management-key=" + validationKey,
                    "--zhijie.relay.revocation-enabled=" + push, "--zhijie.relay.revocation-key=" + revocationKey,
                    "--zhijie.relay.admin-url=http://127.0.0.1:" + port);
            return new Environment(postgres, context, validationKey, revocationKey, port);
        } catch (Exception | AssertionError failure) {
            postgres.close();
            throw failure;
        }
    }

    private record Environment(EmbeddedPostgres postgres, ServletWebServerApplicationContext context,
                               String validationKey, String revocationKey, int managementPort) implements AutoCloseable {
        @Override public void close() throws Exception {
            try { context.close(); } finally { postgres.close(); }
        }
    }
}
