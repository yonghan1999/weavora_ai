package app.weavora.zhijie.controlplane.activity;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.identity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

public enum NetworkProbeRevocation {
    LEAVE, LOGOUT, REFRESH_REPLAY, SESSION_EXPIRED;

    public static AuthTokens login(ServletWebServerApplicationContext context, String deviceId) throws Exception {
        String userId = UUID.randomUUID().toString();
        String email = userId + "@example.test";
        String password = UUID.randomUUID().toString();
        var credentials = context.getBean(CredentialProtector.class);
        context.getBean(IdentityRepository.class).insertUser(new UserAccount(userId, email, AccountStatus.ACTIVE,
                credentials.hashPassword(password), Instant.now()));
        return request(context, "/login", Map.of("email", email, "password", password, "deviceId", deviceId), 200);
    }

    public static AuthTokens refresh(ServletWebServerApplicationContext context, AuthTokens original) throws Exception {
        var rotated = request(context, "/refresh", Map.of("refreshToken", original.refreshToken()), 200);
        assertThat(rotated.sessionId()).isEqualTo(original.sessionId());
        return rotated;
    }

    public static AuthSession session(ServletWebServerApplicationContext context, AuthTokens tokens) {
        return context.getBean(IdentityService.class).authenticateSession("Bearer " + tokens.accessToken());
    }

    public void revoke(ServletWebServerApplicationContext context, AuthTokens original, AuthTokens rotated,
                       String activityId, String leaseId) throws Exception {
        switch (this) {
            case LEAVE -> assertThat(context.getBean(ActivityCoordinator.class)
                    .leave(session(context, rotated), activityId, leaseId)).isTrue();
            case LOGOUT -> request(context, "/logout", Map.of("accessToken", rotated.accessToken()), 200);
            case REFRESH_REPLAY -> request(context, "/refresh", Map.of("refreshToken", original.refreshToken()), 401);
            case SESSION_EXPIRED -> assertThat(context.getBean(JdbcTemplate.class).update(
                    "UPDATE auth_sessions SET expires_at=now()-interval '1 second' WHERE id=? AND revoked_at IS NULL",
                    original.sessionId())).isEqualTo(1);
        }
    }

    public static void assertPersisted(ServletWebServerApplicationContext context, String userId,
                                       String activityId, String leaseId, long confirmed) throws Exception {
        var sql = context.getBean(JdbcTemplate.class);
        while (sql.queryForObject("SELECT revoked_at IS NULL FROM connection_leases WHERE id=?", Boolean.class, leaseId)
                && System.nanoTime() - confirmed < TimeUnit.SECONDS.toNanos(5)) Thread.sleep(10);
        assertThat(sql.queryForObject("SELECT revoked_at IS NOT NULL FROM connection_leases WHERE id=?", Boolean.class, leaseId)).isTrue();
        assertThat(sql.queryForObject("SELECT count(*) FROM account_activity WHERE user_id=?", Integer.class, userId)).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM activity_grants WHERE lease_id=? AND revoked_at IS NULL",
                Integer.class, leaseId)).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox_events WHERE activity_id=? AND aggregate_id=? AND type='network.revoked'",
                Integer.class, activityId, userId)).isEqualTo(1);
        assertThat(System.nanoTime() - confirmed).isLessThan(TimeUnit.SECONDS.toNanos(5));
    }

    private static AuthTokens request(ServletWebServerApplicationContext context, String path,
                                      Map<String, String> body, int expected) throws Exception {
        var json = context.getBean(ObjectMapper.class);
        // 首次登录是准备步骤（冷启动 MVC 和密码校验），不计入撤销生效时间。
        var timeout = Duration.ofSeconds(path.equals("/login") ? 10 : 2);
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + "/api/v1/auth" + path))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("Authentication HTTP %s", path).isEqualTo(expected);
            return expected == 200 && !path.equals("/logout")
                    ? json.treeToValue(json.readTree(response.body()).path("data"), AuthTokens.class) : null;
        }
    }
}
