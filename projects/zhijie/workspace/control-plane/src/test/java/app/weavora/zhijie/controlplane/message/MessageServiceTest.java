package app.weavora.zhijie.controlplane.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.weavora.zhijie.controlplane.audit.AuditService;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.group.GroupMember;
import app.weavora.zhijie.controlplane.group.GroupRole;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.group.MemberStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageServiceTest {
    private InMemoryMessageRepository repository;
    private MessageService service;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        repository = new InMemoryMessageRepository();
        GroupService groups = mock(GroupService.class);
        audit = mock(AuditService.class);
        when(groups.requireMembership(anyString(), anyString())).thenAnswer(invocation -> new GroupMember(
                invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(1).equals("owner") ? GroupRole.OWNER : GroupRole.MEMBER,
                MemberStatus.ACTIVE, Instant.now(), null
        ));
        service = new MessageService(repository, groups, audit, Clock.systemUTC(),
                mock(app.weavora.zhijie.controlplane.sync.OutboxService.class));
    }

    @Test
    void historyRetainsMessagesAndAuthorDeletionReturnsTombstone() {
        MessageView posted = service.post("author", "group", "今晚八点联机");
        MessageView deleted = service.delete("author", posted.id(), "correlation");

        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.body()).isNull();
        assertThat(deleted.deletionHint()).isEqualTo("消息已删除");
        assertThat(repository.findById(posted.id()).orElseThrow().body()).isEqualTo("今晚八点联机");
        assertThat(service.list("author", "group", null, 50)).containsExactly(deleted);
    }

    @Test
    void moderatorDeletionAddsAuditButAuthorDeletionDoesNotPretendModeration() {
        MessageView posted = service.post("author", "group", "消息");
        MessageView deleted = service.delete("owner", posted.id(), "correlation");

        assertThat(deleted.deleteKind()).isEqualTo(DeleteKind.MODERATOR);
        verify(audit).record("group", "owner", "MESSAGE", posted.id(), "MESSAGE_DELETE", "SUCCESS", "correlation");
    }

    @Test
    void ordinaryMemberCannotDeleteAnotherMembersMessage() {
        MessageView posted = service.post("author", "group", "消息");
        assertThatThrownBy(() -> service.delete("other", posted.id(), "correlation"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CURRENT_GRANT_REQUIRED");
        assertThat(repository.findById(posted.id()).orElseThrow().deletedAt()).isNull();
    }

    @Test
    void paginationAndValidationUseStableLimits() {
        MessageView first = service.post("author", "group", "第一条");
        MessageView second = service.post("author", "group", "第二条");
        assertThat(service.list("author", "group", null, 1)).hasSize(1);
        assertThat(service.list("author", "group", second.createdAt(), 1000)).hasSizeLessThanOrEqualTo(100);
        assertThatThrownBy(() -> service.post("author", "group", " "))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("VALIDATION_FAILED");
        assertThat(repository.findById(first.id())).isPresent();
    }

    private static class InMemoryMessageRepository implements MessageRepository {
        private final Map<String, GroupMessage> messages = new HashMap<>();
        public void insert(GroupMessage message) { messages.put(message.id(), message); }
        public Optional<GroupMessage> findById(String messageId) { return Optional.ofNullable(messages.get(messageId)); }
        public void update(GroupMessage message) { messages.put(message.id(), message); }
        public List<GroupMessage> list(String groupId, Instant before, int limit) {
            return messages.values().stream().filter(m -> m.groupId().equals(groupId))
                    .filter(m -> before == null || m.createdAt().isBefore(before))
                    .sorted((a, b) -> b.createdAt().compareTo(a.createdAt())).limit(limit).toList();
        }
    }
}
