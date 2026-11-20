package app.weavora.zhijie.controlplane.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.*;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.room.RoomService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.DriverManager;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;

class ActivitySessionMigrationIntegrationTest {
    @Test
    void upgradeRejectsUnboundLegacyActivityWithoutGuessingAndStaleCleanupPreservesNewSession() throws Exception {
        try (var postgres = EmbeddedPostgres.start()) {
            String url = postgres.getJdbcUrl("postgres", "postgres");
            Flyway.configure().dataSource(url, "postgres", "").target("16").load().migrate();
            try (var connection = DriverManager.getConnection(url, "postgres", ""); var sql = connection.createStatement()) {
                sql.execute("INSERT INTO users(id,email_normalized,status,password_hash,created_at) VALUES ('legacy','legacy@example.test','ACTIVE','unused',NOW())");
                sql.execute("INSERT INTO auth_sessions(id,user_id,device_id,refresh_token_hash,access_token_hash,expires_at,access_expires_at,generation) "
                        + "VALUES ('possible-login','legacy','device','refresh','access',NOW()+INTERVAL '1 hour',NOW()+INTERVAL '1 hour',1)");
                sql.execute("INSERT INTO groups(id,name,description,owner_user_id,status,created_at) VALUES ('legacy-group','legacy','','legacy','ACTIVE',NOW())");
                sql.execute("INSERT INTO group_members(group_id,user_id,role,status,joined_at) VALUES ('legacy-group','legacy','OWNER','ACTIVE',NOW())");
                sql.execute("INSERT INTO rooms(id,group_id,name,description,game_name,owner_user_id,open_state,lifecycle) "
                        + "VALUES ('legacy-room','legacy-group','legacy','','','legacy','OPEN','AVAILABLE')");
                sql.execute("INSERT INTO activities(id,room_id,activity_generation,state,started_at) VALUES ('legacy-activity','legacy-room',1,'ACTIVE',NOW())");
                sql.execute("UPDATE rooms SET current_activity_id='legacy-activity' WHERE id='legacy-room'");
                sql.execute("INSERT INTO activity_members(activity_id,user_id,device_id,member_generation,state,joined_at) "
                        + "VALUES ('legacy-activity','legacy','device',1,'ACTIVE',NOW())");
                sql.execute("INSERT INTO account_activity(user_id,activity_id,device_id,member_generation,operation_id) VALUES ('legacy','legacy-activity','device',1,'legacy')");
                sql.execute("INSERT INTO connection_leases(id,activity_id,user_id,device_id,activity_generation,member_generation,policy_revision,voice_state,network_state,expires_at) "
                        + "VALUES ('legacy-lease','legacy-activity','legacy','device',1,1,1,'CONNECTED','CONNECTED',NOW()+INTERVAL '1 minute')");
                sql.execute("INSERT INTO ip_allocations(activity_id,user_id,ipv4,allocated_at) VALUES ('legacy-activity','legacy','10.231.0.1',NOW())");
            }
            var application = new SpringApplication(ControlPlaneApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            try (var context = application.run("--spring.datasource.url=" + url, "--spring.datasource.username=postgres",
                    "--spring.datasource.password=", "--zhijie.scheduling.enabled=false")) {
                var sql = context.getBean(JdbcTemplate.class);
                var activities = context.getBean(ActivityRepository.class);
                assertThat(sql.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='17' AND success", Integer.class)).isOne();
                assertThat(activities.findAccountActivity("legacy").orElseThrow().authSessionId()).isNull();
                assertThat(context.getBean(ActivitySessionMapper.class).invalidUsers(Instant.now())).containsExactly("legacy");
                assertThatThrownBy(() -> sql.update("UPDATE account_activity SET auth_session_id='missing' WHERE user_id='legacy'"))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(() -> context.getBean(ActivityCoordinator.class).resume("legacy", "legacy-activity", "device"))
                        .isInstanceOf(ApiException.class);
                var revocation = context.getBean(ActivitySessionRevocation.class);
                revocation.revokeInvalid("legacy");
                assertThat(activities.findAccountActivity("legacy")).isEmpty();
                assertThat(activities.findLease("legacy-lease").orElseThrow().revokedAt()).isNotNull();
                assertThat(activities.findAddress("legacy-activity", "legacy").orElseThrow().releasedAt()).isNotNull();
                var session = context.getBean(IdentityRepository.class).findSessionById("possible-login").orElseThrow();
                var joined = context.getBean(ActivityCoordinator.class).join(session, "legacy-room", null, "explicit-rejoin");
                assertThat(joined.memberGeneration()).isGreaterThan(1);
                assertThat(activities.findAccountActivity("legacy").orElseThrow().authSessionId()).isEqualTo(session.id());
                revocation.revokeInvalid("legacy");
                assertThat(activities.findLease(joined.leaseId()).orElseThrow().revokedAt()).isNull();
                assertThat(context.getBean(ActivitySessionMapper.class).invalidUsers(Instant.now())).isEmpty();
                assertThat(context.getBean(RoomService.class).getRoom("legacy", "legacy-room").name()).isEqualTo("legacy");
                assertThat(sql.queryForObject("SELECT count(*) FROM auth_sessions WHERE id='possible-login' AND revoked_at IS NULL", Integer.class)).isOne();
                sql.update("UPDATE auth_sessions SET expires_at=now()-INTERVAL '1 second' WHERE id='possible-login'");
                assertThat(context.getBean(ActivitySessionMapper.class).invalidUsers(Instant.now())).containsExactly("legacy");
                revocation.revokeInvalid("legacy");
                assertThat(activities.findLease(joined.leaseId()).orElseThrow().revokedAt()).isNotNull();
                assertThat(activities.findAccountActivity("legacy")).isEmpty();
                System.out.println("ACTIVITY_SESSION_UPGRADE legacy_unbound_denied=true explicit_rejoin_preserved=true");
            }
        }
    }
}
