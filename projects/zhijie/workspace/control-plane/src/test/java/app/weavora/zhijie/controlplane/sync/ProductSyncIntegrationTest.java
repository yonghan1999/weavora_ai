package app.weavora.zhijie.controlplane.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.group.*;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.room.RoomService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ProductSyncIntegrationTest {
    @Test void governanceNotifiesCurrentMembersAndRemovedUsersWithoutLeakingOtherGroups() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identities = context.getBean(IdentityRepository.class);
            var groups = context.getBean(GroupService.class);
            var rooms = context.getBean(RoomService.class);
            var outbox = context.getBean(OutboxRepository.class);
            var sync = context.getBean(SyncService.class);
            var sql = context.getBean(JdbcTemplate.class);
            String owner = user(identities), member = user(identities), other = user(identities);
            var group = groups.createGroup(owner, "原群名", null, "");
            groups.acceptInvitation(member, groups.invite(owner, group.id(), member).id());
            long cursor = outbox.latestSequence();
            var credentials = context.getBean(CredentialProtector.class);
            String sessionId = UUID.randomUUID().toString(), secret = credentials.randomSecret();
            identities.insertSession(new AuthSession(sessionId, member, "product-sync", "unused", credentials.hashToken(secret),
                    Instant.now().plusSeconds(900), Instant.now().plusSeconds(900), null, 1L));
            var listener = new Collector(context.getBean(ObjectMapper.class));
            int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(
                    "ws://127.0.0.1:" + port + "/api/v1/ws?access_token=" + sessionId + "." + secret + "&cursor=" + cursor), listener).get(5, TimeUnit.SECONDS);
            groups.updateGroup(owner, group.id(), "新群名", null, "");
            groups.changeRole(owner, group.id(), member, GroupRole.ADMIN);
            var room = rooms.createRoom(owner, group.id(), "房间", "", "", "private-room-password");
            rooms.setOpenState(owner, room.id(), app.weavora.zhijie.controlplane.room.RoomOpenState.PAUSED);
            assertThat(sync.history(member, cursor)).extracting(SyncEvent::type)
                    .containsExactly("group.changed", "member.changed", "room.changed", "room.changed");
            assertThat(sync.history(other, cursor)).isEmpty();
            assertThat(sync.history(member, cursor)).allMatch(event -> event.payload().equals("{}") && event.activityId() == null);
            context.getBean(app.weavora.zhijie.controlplane.revocation.RevocationDispatcher.class).dispatchPending();
            listener.awaitCount(4);
            assertThat(listener.events).extracting(event -> event.path("type").asText())
                    .containsExactly("group.changed", "member.changed", "room.changed", "room.changed");
            assertThat(groups.getGroup(member, group.id()).name()).isEqualTo("新群名");
            assertThat(groups.listMembers(member, group.id())).anyMatch(item -> item.userId().equals(member) && item.role() == GroupRole.ADMIN);

            rooms.setOpenState(owner, room.id(), app.weavora.zhijie.controlplane.room.RoomOpenState.OPEN);
            var activity = rooms.startActivity(owner, room.id());
            var coordinator = context.getBean(app.weavora.zhijie.controlplane.activity.ActivityCoordinator.class);
            long activityCursor = outbox.latestSequence();
            coordinator.join(member, room.id(), "device", null, "join");
            coordinator.disconnect(member, activity.id(), "device");
            coordinator.resume(member, activity.id(), "device");
            coordinator.leave(member, activity.id());
            assertThat(sync.history(owner, activityCursor).stream().filter(event -> event.type().equals("activity.changed")))
                    .hasSize(4);

            cursor = outbox.latestSequence();
            // 活动退出会主动关闭原同步连接；以当前游标建立独立群成员连接。
            context.getBean(app.weavora.zhijie.controlplane.revocation.RevocationDispatcher.class).dispatchPending();
            socket.abort();
            listener = new Collector(context.getBean(ObjectMapper.class));
            socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(
                    "ws://127.0.0.1:" + port + "/api/v1/ws?access_token=" + sessionId + "." + secret + "&cursor=" + cursor), listener).get(5, TimeUnit.SECONDS);
            groups.removeMember(owner, group.id(), member);
            var removed = sync.history(member, cursor);
            assertThat(removed).singleElement().satisfies(event -> {
                assertThat(event.type()).isEqualTo("user.changed");
                assertThat(event.groupId()).isNull(); assertThat(event.payload()).isEqualTo("{}");
            });
            assertThat(sync.history(other, cursor)).isEmpty();
            assertThat(sync.history(owner, cursor)).extracting(SyncEvent::type).containsExactly("member.changed");
            assertThat(groups.listGroups(member)).isEmpty();
            context.getBean(app.weavora.zhijie.controlplane.revocation.RevocationDispatcher.class).dispatchPending();
            listener.awaitCount(1);
            assertThat(listener.events).singleElement().satisfies(event -> {
                assertThat(event.path("type").asText()).isEqualTo("user.changed");
                assertThat(event.path("groupId").isNull()).isTrue();
            });

            // 邀请发给尚无群籍的账号，也必须只对收件人产生最小通知。
            cursor = outbox.latestSequence();
            groups.invite(owner, group.id(), other);
            assertThat(sync.history(other, cursor)).extracting(SyncEvent::type).containsExactly("user.changed");
            assertThat(sync.history(member, cursor)).isEmpty();

            int before = sql.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
            var transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> transactions.execute(status -> {
                groups.updateGroup(owner, group.id(), "应回滚", null, "");
                throw new IllegalStateException("rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(sql.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(before);
            assertThat(groups.getGroup(owner, group.id()).name()).isEqualTo("新群名");

            cursor = outbox.latestSequence();
            groups.dissolveGroup(owner, group.id());
            assertThat(sync.history(owner, cursor)).extracting(SyncEvent::type).containsExactly("user.changed");
            assertThat(groups.listGroups(owner)).isEmpty();
            assertThat(sync.history(member, cursor)).isEmpty();
            assertThat(sync.history(other, cursor)).isEmpty();
            socket.abort();
        }
    }

    private String user(IdentityRepository identities) {
        String id = UUID.randomUUID().toString();
        identities.insertUser(new UserAccount(id, id + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
        return id;
    }

    private static class Collector implements WebSocket.Listener {
        final List<JsonNode> events = new CopyOnWriteArrayList<>();
        final StringBuilder partial = new StringBuilder();
        final ObjectMapper json;
        Collector(ObjectMapper json) { this.json = json; }
        public void onOpen(WebSocket socket) { socket.request(1); }
        public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            partial.append(text);
            if (last) {
                try { events.add(json.readTree(partial.toString())); }
                catch (Exception failure) { throw new IllegalStateException("Invalid test sync event"); }
                partial.setLength(0);
            }
            socket.request(1); return null;
        }
        void awaitCount(long expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (events.size() < expected && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat((long)events.size()).isEqualTo(expected);
        }
    }
}
