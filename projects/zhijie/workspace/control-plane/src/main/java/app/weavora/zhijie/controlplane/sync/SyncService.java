package app.weavora.zhijie.controlplane.sync;

import app.weavora.zhijie.controlplane.activity.ActivityMember;
import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.activity.MemberState;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.AuthSession;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private final Map<String, Map<WebSocketSession, Connection>> sessions = new ConcurrentHashMap<>();
    private final OutboxRepository outbox;
    private final GroupService groups;
    private final ActivityRepository activities;
    private final ObjectMapper objectMapper;
    private final IdentityService identity;

    public SyncService(OutboxRepository outbox, GroupService groups, ActivityRepository activities, ObjectMapper objectMapper, IdentityService identity) {
        this.outbox = outbox;
        this.groups = groups;
        this.activities = activities;
        this.objectMapper = objectMapper;
        this.identity = identity;
    }

    public void connect(AuthSession binding, WebSocketSession session, long cursor) throws IOException {
        String userId = binding.userId();
        var connection = new Connection(session, cursor, binding);
        // 先登记再回放；同一连接的广播等待回放锁，不并发写 WebSocket。
        synchronized (connection) {
            sessions.compute(userId, (id, current) -> {
                if (current == null) current = new ConcurrentHashMap<>();
                current.put(session, connection);
                return current;
            });
            try {
                connection.requireValid();
                replay(userId, cursor, event -> connection.send(event.sequence(), encode(event)));
            } catch (RuntimeException exception) {
                unregister(userId, session);
                if (exception.getCause() instanceof IOException io) throw io;
                throw exception;
            }
        }
    }

    public void unregister(String userId, WebSocketSession session) {
        sessions.computeIfPresent(userId, (id, current) -> {
            current.remove(session);
            return current.isEmpty() ? null : current;
        });
    }

    public List<SyncEvent> history(String userId, long cursor) {
        var result = new ArrayList<SyncEvent>();
        replay(userId, cursor, result::add);
        return result;
    }

    private void replay(String userId, long cursor, Consumer<SyncEvent> receive) {
        long end = outbox.latestSequence();
        if (cursor < 0 || cursor > end) {
            throw new ApiException(HttpStatus.CONFLICT, "RESYNC_REQUIRED", "同步游标不可用，请重新获取快照", true);
        }
        int scanned = 0;
        while (cursor < end) {
            var page = outbox.listAfter(cursor, 100);
            if (page.isEmpty()) break;
            for (var event : page) {
                if (event.sequence() > end) return;
                // 权限过滤前推进全局游标，隐藏群的一整页不能截断后续有效消息。
                cursor = event.sequence();
                if (++scanned > 10000) {
                    throw new ApiException(HttpStatus.CONFLICT, "RESYNC_REQUIRED", "同步积压过多，请重新获取快照", true);
                }
                if (authorized(userId, event)) receive.accept(SyncEvent.from(event));
            }
            if (page.size() < 100) break;
        }
    }

    public void broadcast(OutboxEvent event) {
        TextMessage message = null;
        for (var entry : sessions.entrySet()) {
            for (var connection : entry.getValue().values()) {
                var session = connection.session;
                synchronized (connection) {
                    if (!validOrClose(entry.getKey(), session, connection)) continue;
                    if (event.sequence() <= connection.cursor) continue;
                    if (authorized(entry.getKey(), event)) {
                        try {
                            if (message == null) message = encode(SyncEvent.from(event));
                            connection.send(event.sequence(), message);
                        }
                        catch (ApiException invalid) {
                            unregister(entry.getKey(), session);
                            try { session.close(new CloseStatus(1008, "ACCESS_TOKEN_INVALID")); } catch (IOException ignored) { }
                        }
                        catch (RuntimeException failure) {
                            unregister(entry.getKey(), session);
                            log.warn("Sync delivery failed: category={}", failure.getClass().getSimpleName());
                            try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) { }
                        }
                    }
                }
            }
        }
    }

    private TextMessage encode(SyncEvent event) {
        try { return new TextMessage(objectMapper.writeValueAsString(event)); }
        catch (IOException failure) { throw new IllegalStateException("Sync encoding failed", failure); }
    }

    @Scheduled(fixedDelay = 1000)
    public void revalidateSessions() {
        sessions.forEach((userId, connections) -> connections.forEach((session, connection) -> {
            synchronized (connection) { validOrClose(userId, session, connection); }
        }));
    }

    private boolean validOrClose(String userId, WebSocketSession session, Connection connection) {
        try { connection.requireValid(); return true; }
        catch (ApiException failure) {
            unregister(userId, session);
            try { session.close(new CloseStatus(1008, "ACCESS_TOKEN_INVALID")); } catch (IOException ignored) { }
            return false;
        }
    }

    public void closeUser(String userId) {
        for (WebSocketSession session : sessions.getOrDefault(userId, Map.of()).keySet()) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException ignored) {
            }
        }
        sessions.remove(userId);
    }

    int sessionCount(String userId) {
        return sessions.getOrDefault(userId, Map.of()).size();
    }

    private boolean authorized(String userId, OutboxEvent event) {
        try {
            if (event.type().equals("user.changed")) {
                if (!userId.equals(event.aggregateId())) return false;
                identity.requireActive(userId);
                return true;
            }
            if (event.groupId() != null) groups.requireMembership(event.groupId(), userId);
            if (event.activityId() != null) {
                ActivityMember member = activities.findMember(event.activityId(), userId).orElse(null);
                if (member == null || member.state() != MemberState.ACTIVE && member.state() != MemberState.GRACE) return false;
                if (event.generation() != null && !event.generation().equals(member.memberGeneration())) return false;
            }
            return true;
        } catch (ApiException exception) {
            return false;
        }
    }

    private class Connection {
        private final WebSocketSession session;
        private long cursor;
        private final String sessionId;
        private final long generation;
        private final String userId;
        Connection(WebSocketSession session, long cursor, AuthSession binding) {
            this.session = session; this.cursor = cursor;
            this.sessionId = binding.id(); this.generation = binding.generation(); this.userId = binding.userId();
        }
        void requireValid() { identity.requireSession(sessionId, generation, userId); }
        void send(long sequence, TextMessage message) {
            if (sequence <= cursor) return;
            requireValid();
            try {
                session.sendMessage(message);
                cursor = sequence;
            } catch (IOException failure) { throw new IllegalStateException("Sync delivery failed", failure); }
        }
    }
}
