package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;

import app.weavora.zhijie.controlplane.activity.ActivityMember;
import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.activity.MemberState;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.identity.AuthSession;
import app.weavora.zhijie.controlplane.identity.IdentityService;
import app.weavora.zhijie.controlplane.group.GroupMember;
import app.weavora.zhijie.controlplane.group.GroupRole;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.group.MemberStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.stream.LongStream;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;

class SyncServiceTest {
    @Test void hundredRecipientsShareOneEncodingButEachStillRequiresAValidSession() throws Exception {
        var outbox = mock(OutboxRepository.class);
        var identity = mock(IdentityService.class);
        var json = org.mockito.Mockito.spy(new ObjectMapper().findAndRegisterModules());
        var groups = mock(GroupService.class);
        var service = new SyncService(outbox, groups, mock(ActivityRepository.class), json, identity);
        var sockets = new java.util.ArrayList<WebSocketSession>();
        for (int index = 0; index < 100; index++) {
            var socket = mock(WebSocketSession.class);
            var binding = new AuthSession("session-" + index, "user-" + index, "device", "unused", "unused",
                    Instant.now().plusSeconds(60), Instant.now().plusSeconds(60), null, 1L);
            service.connect(binding, socket, 0);
            sockets.add(socket);
        }
        doThrow(new ApiException(HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID", "凭据无效", false))
                .when(identity).requireSession("session-99", 1, "user-99");
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "群资格无效", false))
                .when(groups).requireMembership("group", "user-98");
        var event = new OutboxEvent(1L, "event-1", "group", null, "message", "message.created",
                "{}", null, null, Instant.now(), null, 0);
        service.broadcast(event);
        for (var socket : sockets.subList(0, 98)) verify(socket).sendMessage(any(TextMessage.class));
        verify(sockets.get(98), never()).sendMessage(any(TextMessage.class));
        verify(sockets.get(99), never()).sendMessage(any(TextMessage.class));
        verify(sockets.get(99)).close(new CloseStatus(1008, "ACCESS_TOKEN_INVALID"));
        verify(json).writeValueAsString(SyncEvent.from(event));
        service.broadcast(event);
        verify(json).writeValueAsString(SyncEvent.from(event));
    }

    @Test void invalidSessionIsRejectedBeforeLiveDeliveryWithoutWaitingForScheduler() throws Exception {
        var outbox = mock(OutboxRepository.class);
        when(outbox.latestSequence()).thenReturn(0L);
        var identity = mock(IdentityService.class);
        var service = new SyncService(outbox, mock(GroupService.class), mock(ActivityRepository.class), new ObjectMapper(), identity);
        var socket = mock(WebSocketSession.class);
        service.connect(binding(), socket, 0);
        doThrow(new ApiException(HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID", "凭据无效", false))
                .when(identity).requireSession("session", 1, "user");
        service.broadcast(globalEvent(1));
        verify(socket, never()).sendMessage(any(TextMessage.class));
        verify(socket).close(new CloseStatus(1008, "ACCESS_TOKEN_INVALID"));
        assertThat(service.sessionCount("user")).isZero();
    }

    @Test void concurrentBroadcastWaitsForReplayAndDoesNotWriteTheSameSocketTwice() throws Exception {
        var outbox = mock(OutboxRepository.class);
        when(outbox.latestSequence()).thenReturn(2L);
        when(outbox.listAfter(0, 100)).thenReturn(List.of(globalEvent(1), globalEvent(2)));
        var json = new ObjectMapper().findAndRegisterModules();
        var service = new SyncService(outbox, mock(GroupService.class), mock(ActivityRepository.class), json, mock(IdentityService.class));
        var socket = mock(WebSocketSession.class);
        var firstSend = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delivered = new CopyOnWriteArrayList<Long>();
        doAnswer(call -> {
            long sequence = json.readTree(((TextMessage)call.getArgument(0)).getPayload()).path("sequence").asLong();
            if (sequence == 1) { firstSend.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); }
            delivered.add(sequence);
            return null;
        }).when(socket).sendMessage(any(TextMessage.class));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var replay = workers.submit(() -> { service.connect(binding(), socket, 0); return null; });
            assertThat(firstSend.await(3, TimeUnit.SECONDS)).isTrue();
            var entered = new CountDownLatch(1);
            var live = workers.submit(() -> { entered.countDown(); service.broadcast(globalEvent(3)); });
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(() -> live.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            replay.get(3, TimeUnit.SECONDS); live.get(3, TimeUnit.SECONDS);
            service.broadcast(globalEvent(2));
            assertThat(delivered).containsExactly(1L, 2L, 3L);
            service.unregister("user", socket);
            assertThat(service.sessionCount("user")).isZero();
        }
    }

    @Test void replayHasAFixedScanLimitAndExplicitlyRequiresSnapshotBeyondIt() {
        var outbox = mock(OutboxRepository.class);
        when(outbox.latestSequence()).thenReturn(10001L);
        when(outbox.listAfter(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(100)))
                .thenAnswer(call -> {
                    long cursor = call.getArgument(0);
                    return LongStream.rangeClosed(cursor + 1, Math.min(cursor + 100, 10001)).mapToObj(this::globalEvent).toList();
                });
        var service = new SyncService(outbox, mock(GroupService.class), mock(ActivityRepository.class), new ObjectMapper(), mock(IdentityService.class));
        assertThatThrownBy(() -> service.history("user", 0)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("RESYNC_REQUIRED");
    }

    @Test void rejectedReplayDoesNotLeaveARegisteredSocket() {
        var outbox = mock(OutboxRepository.class);
        when(outbox.latestSequence()).thenReturn(2L);
        var service = new SyncService(outbox, mock(GroupService.class), mock(ActivityRepository.class), new ObjectMapper(), mock(IdentityService.class));
        var socket = mock(WebSocketSession.class);
        assertThatThrownBy(() -> service.connect(binding(), socket, 3)).isInstanceOf(ApiException.class);
        assertThat(service.sessionCount("user")).isZero();
    }

    private OutboxEvent globalEvent(long sequence) {
        return new OutboxEvent(sequence, "event-" + sequence, null, null, "test", "test.changed",
                "{}", null, null, Instant.now(), null, 0);
    }

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
        SyncService service = new SyncService(outbox, groups, activities, new ObjectMapper(), mock(IdentityService.class));

        assertThat(service.history("user", 0)).extracting(SyncEvent::eventId).containsExactly("event-2");
        assertThatThrownBy(() -> service.history("user", 3))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RESYNC_REQUIRED");
    }

    private OutboxEvent event(long sequence, long generation) {
        return new OutboxEvent(sequence, "event-" + sequence, "group", "activity", "user", "activity.changed",
                "{}", generation, 1L, Instant.now(), null, 0);
    }

    private AuthSession binding() {
        return new AuthSession("session", "user", "device", "unused-refresh-hash", "unused-access-hash",
                Instant.now().plusSeconds(60), Instant.now().plusSeconds(60), null, 1L);
    }
}
