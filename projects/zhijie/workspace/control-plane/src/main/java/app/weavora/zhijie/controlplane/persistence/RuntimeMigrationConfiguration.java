package app.weavora.zhijie.controlplane.persistence;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** 运行进程只校验迁移；数据库所有者凭据不进入长期运行的控制面。 */
@Configuration
@Profile("runtime")
public class RuntimeMigrationConfiguration {
    @Bean
    FlywayMigrationStrategy validateExistingSchema() {
        return flyway -> {
            var sql = new JdbcTemplate(flyway.getConfiguration().getDataSource());
            Boolean excessive = sql.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=current_user
                        AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
                        OR has_schema_privilege(current_user, 'public', 'CREATE')
                        OR has_database_privilege(current_user, current_database(), 'TEMPORARY')
                        OR has_table_privilege(current_user, 'audit_logs', 'UPDATE,DELETE,TRUNCATE,TRIGGER')
                        OR has_table_privilege(current_user, 'flyway_schema_history', 'INSERT,UPDATE,DELETE,TRUNCATE')
                        OR has_table_privilege(current_user, 'owner_recovery_requests', 'UPDATE,DELETE,TRUNCATE')
                        OR has_any_column_privilege(current_user, 'owner_recovery_requests', 'UPDATE')
                        OR EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='public'
                            AND pg_has_role(current_user, tableowner, 'MEMBER'))
                    """, Boolean.class);
            if (!Boolean.FALSE.equals(excessive)) {
                throw new IllegalStateException("RUNTIME_DATABASE_ROLE_EXCESSIVE");
            }
            flyway.validate();
        };
    }
}
