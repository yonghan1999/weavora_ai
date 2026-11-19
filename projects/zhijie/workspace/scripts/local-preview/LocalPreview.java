import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.message.MessageService;
import app.weavora.zhijie.controlplane.room.RoomService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 独立本机试用入口，不打包进正式服务。 */
public class LocalPreview {
    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    private static int run(String[] args) throws Exception {
        int httpPort = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        int databasePort = args.length == 0 ? 15432 : Integer.parseInt(args[1]);
        var data = Path.of("target/local-preview/postgres").toAbsolutePath();
        try (var postgres = EmbeddedPostgres.builder().setDataDirectory(data)
                .setCleanDataDirectory(false).setPort(databasePort)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
             var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=" + httpPort,
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            if (!seed(context)) {
                System.out.println("LOCAL_PREVIEW_SEED_PASSWORD_REQUIRED");
                return 1;
            }
            System.out.println("LOCAL_PREVIEW_READY http://127.0.0.1:" + httpPort + " account=trial@example.test");
            while (!Files.exists(data.getParent().resolve("stop"))) Thread.sleep(1000);
            return 0;
        }
    }

    private static boolean seed(ConfigurableApplicationContext context) {
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        return transaction.execute(status -> {
            var users = context.getBean(IdentityRepository.class);
            String email = "trial@example.test";
            if (users.findUserByEmail(email).isPresent()) return true;
            String password = System.getenv("ZHIJIE_PREVIEW_PASSWORD");
            if (password == null || password.length() < 12) return false;
            String userId = UUID.randomUUID().toString();
            users.insertUser(new UserAccount(userId, email, AccountStatus.ACTIVE,
                    context.getBean(CredentialProtector.class).hashPassword(password), Instant.now()));
            var group = context.getBean(GroupService.class).createGroup(userId, "本地试用群", null, "仅供本机试用");
            var rooms = context.getBean(RoomService.class);
            var room = rooms.createRoom(userId, group.id(), "一起试试", "本地功能体验房间", "自定义游戏", null);
            rooms.startActivity(userId, room.id());
            context.getBean(MessageService.class).post(userId, group.id(), "欢迎体验织界。当前使用本机试用环境；语音和联机是否可用以房间连接状态为准。");
            return true;
        });
    }
}
