package app.weavora.zhijie.controlplane.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.group.Group;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.AccountStatus;
import app.weavora.zhijie.controlplane.identity.CredentialProtector;
import app.weavora.zhijie.controlplane.identity.IdentityRepository;
import app.weavora.zhijie.controlplane.identity.UserAccount;
import app.weavora.zhijie.controlplane.room.Room;
import app.weavora.zhijie.controlplane.room.RoomService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

class VerticalSliceIntegrationTest {
    @Test
    void rustClientUsesRealHttpServiceThroughJoinFailureIsolationAndCleanup() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            SpringApplication application = new SpringApplication(ControlPlaneApplication.class);
            application.setWebApplicationType(WebApplicationType.SERVLET);
            ConfigurableApplicationContext context = application.run(
                    "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                    "--spring.datasource.username=postgres", "--spring.datasource.password=",
                    "--zhijie.scheduling.enabled=false", "--zhijie.voice.enabled=false", "--server.address=127.0.0.1", "--server.port=0"
            );
            try {
                int port = ((WebServerApplicationContext) context).getWebServer().getPort();
                String userId = UUID.randomUUID().toString();
                String password = UUID.randomUUID().toString();
                context.getBean(IdentityRepository.class).insertUser(new UserAccount(userId, "vertical@example.com",
                        AccountStatus.ACTIVE, context.getBean(CredentialProtector.class).hashPassword(password), Instant.now()));
                Group group = context.getBean(GroupService.class).createGroup(userId, "垂直切片", null, "");
                RoomService rooms = context.getBean(RoomService.class);
                Room room = rooms.createRoom(userId, group.id(), "真实房间", "", "", null);
                rooms.startActivity(userId, room.id());
                Path workspace = Path.of(System.getProperty("user.dir")).getParent();
                var json = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
                String build = run(workspace, null, Duration.ofSeconds(120), "cargo", "build", "--offline", "--locked", "-p", "zhijie-core", "--bin", "vertical-slice", "--message-format=json");
                var executables = new java.util.ArrayList<String>();
                for (String line : build.lines().filter(value -> value.startsWith("{")).toList()) {
                    var message = json.readTree(line);
                    if ("compiler-artifact".equals(message.path("reason").asText()) && "vertical-slice".equals(message.path("target").path("name").asText())
                            && message.path("executable").isTextual()) executables.add(message.get("executable").textValue());
                }
                assertThat(executables).hasSize(1);
                String executable = executables.getFirst();
                assertThat(Path.of(executable)).isAbsolute().isRegularFile();
                byte[] configuration = json.writeValueAsBytes(
                        Map.of("baseUrl", "http://127.0.0.1:" + port, "email", "vertical@example.com", "password", password, "roomId", room.id()));
                String output = run(workspace, configuration, Duration.ofSeconds(30), executable);
                assertThat(output.contains(password)).as("Fixture output must not contain the test password").isFalse();
                assertThat(output).contains("\"login\":\"verified\"");
                assertThat(output).contains("\"join\":\"verified\"");
                assertThat(output).contains("\"networkGrant\":\"verified\"");
                assertThat(output).contains("\"voice\":\"unverified_external_sfu\"");
                assertThat(output).contains("\"independentFailure\":\"verified\"");
                assertThat(output).contains("\"leaveCleanup\":\"verified\"");
                assertThat(output).contains("\"tun\":\"unverified_real_device\"");
            } finally {
                context.close();
            }
        }
    }

    @Test void stalledSubprocessIsStoppedAtDeadlineAndPartialOutputIsRetained() throws Exception {
        Path workspace = Path.of(System.getProperty("user.dir")).getParent();
        Path log = Files.createTempFile(workspace.resolve("target"), "vertical-slice-timeout-", ".log");
        var started = Instant.now();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runLogged(workspace, log, null, Duration.ofSeconds(1),
                "node", "-e", "const child=require('node:child_process').spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore',windowsHide:true});process.stdout.write('PARTIAL_OUTPUT\\nCHILD_PID='+child.pid);setInterval(()=>{},1000)"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Subprocess deadline");
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(8));
        String partial = Files.readString(log);
        assertThat(partial).startsWith("PARTIAL_OUTPUT\nCHILD_PID=");
        long childPid = Long.parseLong(partial.substring(partial.indexOf("CHILD_PID=") + 10));
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
                .as("Timed-out subprocess descendant must stop").isFalse();
    }

    private String run(Path workspace, byte[] input, Duration timeout, String... command) throws Exception {
        Path log = Files.createTempFile(workspace.resolve("target"), "vertical-slice-process-", ".log");
        return runLogged(workspace, log, input, timeout, command);
    }

    private String runLogged(Path workspace, Path log, byte[] input, Duration timeout, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(workspace.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try (var stdin = process.getOutputStream()) { if (input != null) stdin.write(input); }
            assertThat(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)).as("Subprocess deadline; diagnostics: %s", log).isTrue();
            assertThat(process.exitValue()).as("Subprocess failed; diagnostics: %s", log).isZero();
            return Files.readString(log, StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) {
                var descendants = process.descendants().toList();
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertThat(process.waitFor(3, TimeUnit.SECONDS)).as("Timed-out subprocess must stop").isTrue();
                for (var child : descendants) if (child.isAlive()) child.onExit().get(3, TimeUnit.SECONDS);
                assertThat(descendants).allMatch(handle -> !handle.isAlive());
            }
        }
    }

}
