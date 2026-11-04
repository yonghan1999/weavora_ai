package app.weavora.zhijie.controlplane.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.group.GroupMember;
import app.weavora.zhijie.controlplane.group.GroupRole;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.group.MemberStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditServiceTest {
    @Test
    void onlyCurrentAdministratorsCanReadAppendOnlyAuditHistory() {
        List<AuditLog> entries = new ArrayList<>();
        AuditRepository repository = new AuditRepository() {
            public void insert(AuditLog entry) { entries.add(entry); }
            public List<AuditLog> list(String groupId, Instant before, int limit) { return List.copyOf(entries); }
        };
        GroupService groups = mock(GroupService.class);
        when(groups.requireMembership("group", "member")).thenReturn(member("member", GroupRole.MEMBER));
        when(groups.requireMembership("group", "owner")).thenReturn(member("owner", GroupRole.OWNER));
        AuditService service = new AuditService(repository, groups, Clock.systemUTC());

        service.record("group", "owner", "MESSAGE", "message", "MESSAGE_DELETE", "SUCCESS", "correlation");
        assertThat(service.list("owner", "group", null, 50)).singleElement()
                .satisfies(entry -> assertThat(entry.action()).isEqualTo("MESSAGE_DELETE"));
        assertThatThrownBy(() -> service.list("member", "group", null, 50))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CURRENT_GRANT_REQUIRED");
    }

    private GroupMember member(String userId, GroupRole role) {
        return new GroupMember("group", userId, role, MemberStatus.ACTIVE, Instant.now(), null);
    }
}
