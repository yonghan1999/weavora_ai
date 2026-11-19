package app.weavora.zhijie.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ControlPlaneApplication {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--migrate-database")) {
            if (args.length != 1) throw new IllegalArgumentException("USAGE: --migrate-database");
            app.weavora.zhijie.controlplane.persistence.DatabaseMigration.run(System.getenv());
            return;
        }
        if (args.length > 0 && args[0].equals("--owner-recovery-review")) {
            app.weavora.zhijie.controlplane.group.OwnerRecoveryTool.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
