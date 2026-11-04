package app.weavora.zhijie.controlplane.message;

import app.weavora.zhijie.controlplane.audit.AuditService;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.group.GroupMember;
import app.weavora.zhijie.controlplane.group.GroupRole;
import app.weavora.zhijie.controlplane.group.GroupService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessageService {
    private final MessageRepository repository;
    private final GroupService groups;
    private final AuditService audit;
    private final Clock clock;
    private final app.weavora.zhijie.controlplane.sync.OutboxService outbox;

    public MessageService(MessageRepository repository, GroupService groups, AuditService audit, Clock clock,
                          app.weavora.zhijie.controlplane.sync.OutboxService outbox) {
        this.repository = repository;
        this.groups = groups;
        this.audit = audit;
        this.clock = clock;
        this.outbox = outbox;
    }

    @Transactional
    public MessageView post(String actorId, String groupId, String body) {
        groups.requireMembership(groupId, actorId);
        if (body == null || body.isBlank() || body.length() > 4000) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "消息须为 1～4000 个字符", false);
        }
        GroupMessage message = new GroupMessage(UUID.randomUUID().toString(), groupId, actorId, body, clock.instant(),
                null, null, DeleteKind.NONE);
        repository.insert(message);
        outbox.record(groupId, null, message.id(), "message.created", "{\"message_id\":\"" + message.id() + "\"}",
                null, null);
        return MessageView.from(message);
    }

    public List<MessageView> list(String actorId, String groupId, Instant before, int limit) {
        groups.requireMembership(groupId, actorId);
        return repository.list(groupId, before, Math.min(Math.max(limit, 1), 100)).stream().map(MessageView::from).toList();
    }

    @Transactional
    public MessageView delete(String actorId, String messageId, String correlationId) {
        GroupMessage message = repository.findById(messageId).orElseThrow(this::messageUnavailable);
        GroupMember actor = groups.requireMembership(message.groupId(), actorId);
        boolean author = message.authorId().equals(actorId);
        boolean moderator = actor.role() == GroupRole.OWNER || actor.role() == GroupRole.ADMIN;
        if (!author && !moderator) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "不能删除其他成员的消息", false);
        }
        if (message.deletedAt() != null) return MessageView.from(message);
        GroupMessage deleted = new GroupMessage(message.id(), message.groupId(), message.authorId(), message.body(),
                message.createdAt(), clock.instant(), actorId, author ? DeleteKind.AUTHOR : DeleteKind.MODERATOR);
        repository.update(deleted);
        outbox.record(message.groupId(), null, message.id(), "message.deleted",
                "{\"message_id\":\"" + message.id() + "\"}", null, null);
        if (!author) {
            audit.record(message.groupId(), actorId, "MESSAGE", message.id(), "MESSAGE_DELETE", "SUCCESS", correlationId);
        }
        return MessageView.from(deleted);
    }

    private ApiException messageUnavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_AVAILABLE", "消息不可用", false);
    }
}
