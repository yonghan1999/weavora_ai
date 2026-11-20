package app.weavora.zhijie.controlplane.activity;

import app.weavora.zhijie.controlplane.identity.AuthSession;
import app.weavora.zhijie.controlplane.identity.IdentityRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

public final class AuthenticatedActivities {
    private AuthenticatedActivities() { }

    public static JoinResult join(ApplicationContext context, String userId, String roomId, String deviceId, String password, String operationId) {
        return context.getBean(ActivityCoordinator.class).join(session(context, userId, deviceId), roomId, password, operationId);
    }

    public static synchronized AuthSession session(ApplicationContext context, String userId, String deviceId) {
        var repository = context.getBean(IdentityRepository.class);
        var ids = context.getBean(JdbcTemplate.class).queryForList(
                "SELECT id FROM auth_sessions WHERE user_id=? AND device_id=? AND revoked_at IS NULL AND access_expires_at>now() "
                        + "AND expires_at>now() ORDER BY expires_at DESC,id LIMIT 1", String.class, userId, deviceId);
        if (!ids.isEmpty()) return repository.findSessionById(ids.getFirst()).orElseThrow();
        var session = new AuthSession(UUID.randomUUID().toString(), userId, deviceId, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(3600), null, 1L);
        repository.insertSession(session);
        return session;
    }
}
