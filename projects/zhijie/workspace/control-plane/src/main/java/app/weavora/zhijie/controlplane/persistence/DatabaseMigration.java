package app.weavora.zhijie.controlplane.persistence;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class DatabaseMigration {
    private DatabaseMigration() {}

    public static void run(Map<String, String> environment) {
        String url = environment.get("ZHIJIE_MIGRATION_DATABASE_URL");
        String password = environment.get("ZHIJIE_MIGRATION_DATABASE_PASSWORD");
        if (url == null || !url.startsWith("jdbc:postgresql://") || password == null || password.isBlank()) {
            throw new IllegalArgumentException("MIGRATION_DATABASE_CONFIGURATION_REQUIRED");
        }
        var source = new DriverManagerDataSource(url, "zhijie_migrator", password);
        Boolean dedicatedRole = new JdbcTemplate(source).queryForObject("""
                SELECT current_user = 'zhijie_migrator' AND NOT
                    (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
                FROM pg_roles WHERE rolname = current_user
                """, Boolean.class);
        if (!Boolean.TRUE.equals(dedicatedRole)) throw new IllegalStateException("MIGRATION_DATABASE_ROLE_REQUIRED");
        var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
        if (java.util.Arrays.stream(flyway.info().all()).noneMatch(info -> info.getState().isResolved())) {
            throw new IllegalStateException("MIGRATION_SCRIPTS_MISSING");
        }
        var result = flyway.migrate();
        flyway.validate();
        System.out.println("DATABASE_MIGRATION_OK applied=" + result.migrationsExecuted);
    }
}
