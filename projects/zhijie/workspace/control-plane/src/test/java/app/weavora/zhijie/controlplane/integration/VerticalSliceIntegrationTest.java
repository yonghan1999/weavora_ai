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
import java.time.Instant;
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
                    "--zhijie.scheduling.enabled=false", "--server.port=0"
            );
            try {
                int port = ((WebServerApplicationContext) context).getWebServer().getPort();
                String userId = UUID.randomUUID().toString();
                String password = "vertical-slice-password";
                context.getBean(IdentityRepository.class).insertUser(new UserAccount(userId, "vertical@example.com",
                        AccountStatus.ACTIVE, context.getBean(CredentialProtector.class).hashPassword(password), Instant.now()));
                Group group = context.getBean(GroupService.class).createGroup(userId, "垂直切片", null, "");
                RoomService rooms = context.getBean(RoomService.class);
                Room room = rooms.createRoom(userId, group.id(), "真实房间", "", "", null);
                rooms.startActivity(userId, room.id());
                Path workspace = Path.of(System.getProperty("user.dir")).getParent();
                run(workspace, "cargo", "build", "-p", "zhijie-core", "--bin", "vertical-slice");
                String executable = workspace.resolve("target/debug/vertical-slice" + (isWindows() ? ".exe" : "")).toString();
                String output = run(workspace, executable, "http://127.0.0.1:" + port,
                        "vertical@example.com", password, room.id());
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

    private String run(Path workspace, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
