package app.weavora.zhijie.controlplane.message;

import java.time.Instant;

public record MessageView(
        String id, String groupId, String authorId, String body, Instant createdAt,
        boolean deleted, String deletionHint, String deletedBy, DeleteKind deleteKind
) {
    public static MessageView from(GroupMessage message) {
        boolean deleted = message.deletedAt() != null;
        return new MessageView(message.id(), message.groupId(), message.authorId(), deleted ? null : message.body(),
                message.createdAt(), deleted, deleted ? "消息已删除" : null, message.deletedBy(), message.deleteKind());
    }
}
