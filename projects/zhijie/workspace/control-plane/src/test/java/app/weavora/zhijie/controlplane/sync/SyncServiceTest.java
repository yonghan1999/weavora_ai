package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.weavora.zhijie.controlplane.activity.ActivityMember;
import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.activity.MemberState;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.group.GroupMember;
import app.weavora.zhijie.controlplane.group.GroupRole;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.group.MemberStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SyncServiceTest {
    @Test
    void historyUsesCursorAuthorizationAndDropsStaleGenerations() {
        OutboxRepository outbox = mock(OutboxRepository.class);
        GroupService groups = mock(GroupService.class);
        ActivityRepository activities = mock(ActivityRepository.class);
        OutboxEvent stale = event(1L, 1L);
        OutboxEvent current = event(2L, 2L);
        when(outbox.latestSequence()).thenReturn(2L);
        when(outbox.listAfter(0, 100)).thenReturn(List.of(stale, current));
        when(groups.requireMembership("group", "user")).thenReturn(new GroupMember("group", "user",
                GroupRole.MEMBER, MemberStatus.ACTIVE, Instant.now(), null));
        when(activities.findMember("activity", "user")).thenReturn(Optional.of(new ActivityMember("activity", "user",
                "device", 2L, MemberState.ACTIVE, Instant.now(), null)));
        SyncService service = new SyncService(outbox, groups, activities, new ObjectMapper());

        assertThat(service.history("user", 0)).extracting(SyncEvent::eventId).containsExactly("event-2");
        assertThatThrownBy(() -> service.history("user", 3))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RESYNC_REQUIRED");
    }

    private OutboxEvent event(long sequence, long generation) {
        return new OutboxEvent(sequence, "event-" + sequence, "group", "activity", "user", "activity.changed",
                "{}", generation, 1L, Instant.now(), null, 0);
    }
}
