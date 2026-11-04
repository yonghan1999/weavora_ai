package app.weavora.zhijie.controlplane.sync;

import app.weavora.zhijie.controlplane.activity.ActivityMember;
import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.activity.MemberState;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.group.GroupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Service
public class SyncService {
    private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final OutboxRepository outbox;
    private final GroupService groups;
    private final ActivityRepository activities;
    private final ObjectMapper objectMapper;

    public SyncService(OutboxRepository outbox, GroupService groups, ActivityRepository activities, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.groups = groups;
        this.activities = activities;
        this.objectMapper = objectMapper;
    }

    public void register(String userId, WebSocketSession session) {
        sessions.computeIfAbsent(userId, ignored -> new CopyOnWriteArraySet<>()).add(session);
    }

    public void unregister(String userId, WebSocketSession session) {
        sessions.computeIfPresent(userId, (id, current) -> {
            current.remove(session);
            return current.isEmpty() ? null : current;
        });
    }

    public List<SyncEvent> history(String userId, long cursor) {
        if (cursor < 0 || cursor > outbox.latestSequence()) {
            throw new ApiException(HttpStatus.CONFLICT, "RESYNC_REQUIRED", "同步游标不可用，请重新获取快照", true);
        }
        return outbox.listAfter(cursor, 100).stream().filter(event -> authorized(userId, event)).map(SyncEvent::from).toList();
    }

    public void broadcast(OutboxEvent event) {
        for (Map.Entry<String, Set<WebSocketSession>> entry : sessions.entrySet()) {
            if (authorized(entry.getKey(), event)) entry.getValue().forEach(session -> send(session, event));
        }
    }

    public void closeUser(String userId) {
        for (WebSocketSession session : sessions.getOrDefault(userId, Set.of())) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException ignored) {
            }
        }
        sessions.remove(userId);
    }

    int sessionCount(String userId) {
        return sessions.getOrDefault(userId, Set.of()).size();
    }

    private boolean authorized(String userId, OutboxEvent event) {
        try {
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

    private void send(WebSocketSession session, OutboxEvent event) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(SyncEvent.from(event))));
        } catch (IOException ignored) {
        }
    }
}
