package app.weavora.zhijie.controlplane.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.revocation.RevocationDispatcher;
import app.weavora.zhijie.controlplane.sync.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SocialSyncIntegrationTest {
    @Test void groupPresenceChangesOnlyReachCurrentMembers() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identities = context.getBean(IdentityRepository.class);
            String owner = user(identities), member = user(identities), outsider = user(identities);
            var groups = context.getBean(app.weavora.zhijie.controlplane.group.GroupService.class);
            var social = context.getBean(SocialService.class);
            var sync = context.getBean(SyncService.class);
            var outbox = context.getBean(OutboxRepository.class);
            var group = groups.createGroup(owner, "私密群", null, "");
            groups.acceptInvitation(member, groups.invite(owner, group.id(), member).id());
            assertThat(groups.listMembers(owner, group.id())).allMatch(row -> row.presence().equals("OFFLINE"));
            long before = outbox.latestSequence();
            social.heartbeat(member); social.heartbeat(member);
            assertThat(sync.history(owner, before)).singleElement().satisfies(event -> {
                assertThat(event.groupId()).isEqualTo(group.id());
                assertThat(event.type()).isEqualTo("member.changed");
                assertThat(event.payload()).isEqualTo("{}");
            });
            assertThat(sync.history(outsider, before)).isEmpty();
            assertThat(groups.listMembers(owner, group.id())).anyMatch(row -> row.userId().equals(member) && row.presence().equals("ONLINE"));
            groups.removeMember(owner, group.id(), member);
            before = outbox.latestSequence();
            social.heartbeat(owner);
            assertThat(sync.history(member, before)).isEmpty();
            assertThatThrownBy(() -> groups.listMembers(member, group.id())).isInstanceOf(app.weavora.zhijie.controlplane.common.ApiException.class);
            assertThat(groups.listMembers(owner, group.id())).extracting(row -> row.userId()).containsExactly(owner);
        }
    }

    @Test void relationshipsAndPresenceDeliverPrivateInvalidationsAndRollbackTogether() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identities = context.getBean(IdentityRepository.class);
            String first = user(identities), second = user(identities), outsider = user(identities);
            var social = context.getBean(SocialService.class);
            var outbox = context.getBean(OutboxRepository.class);
            var sync = context.getBean(SyncService.class);
            var sql = context.getBean(JdbcTemplate.class);
            var credentials = context.getBean(CredentialProtector.class);
            String session = UUID.randomUUID().toString(), secret = credentials.randomSecret();
            identities.insertSession(new AuthSession(session, first, "social-sync", "unused", credentials.hashToken(secret),
                    Instant.now().plusSeconds(900), Instant.now().plusSeconds(900), null, 1L));
            long cursor = outbox.latestSequence();
            var listener = new Collector(context.getBean(ObjectMapper.class));
            int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(
                    "ws://127.0.0.1:" + port + "/api/v1/ws?access_token=" + session + "." + secret + "&cursor=" + cursor), listener).get(5, TimeUnit.SECONDS);
            try {
                var request = social.sendRequest(second, first);
                assertThat(social.listIncomingRequests(first, null)).extracting(IncomingFriendRequest::id).containsExactly(request.id());
                assertPrivate(sync, first, cursor, 1); assertPrivate(sync, second, cursor, 1);
                assertPrivate(sync, outsider, cursor, 0);
                social.accept(first, request.id());
                assertThat(social.listFriends(first)).extracting(FriendView::userId).containsExactly(second);
                social.heartbeat(second);
                assertThat(social.listFriends(first)).extracting(FriendView::presence).containsExactly("ONLINE");
                long stable = outbox.latestSequence();
                social.heartbeat(second);
                assertThat(outbox.latestSequence()).isEqualTo(stable);
                sql.update("UPDATE presence SET last_seen_at = ? WHERE user_id = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(61)), second);
                social.expirePresence();
                assertThat(social.listFriends(first)).extracting(FriendView::presence).containsExactly("OFFLINE");
                stable = outbox.latestSequence(); social.expirePresence();
                assertThat(outbox.latestSequence()).isEqualTo(stable);
                social.heartbeat(second);
                assertThat(social.listFriends(first)).extracting(FriendView::presence).containsExactly("ONLINE");
                assertPrivate(sync, first, cursor, 5); assertPrivate(sync, second, cursor, 2);
                assertPrivate(sync, outsider, cursor, 0);
                context.getBean(RevocationDispatcher.class).dispatchPending();
                listener.awaitCount(5);
                assertThat(listener.events).allMatch(event -> event.path("type").asText().equals("user.changed")
                        && event.path("groupId").isNull() && event.path("payload").asText().equals("{}"));

                // 过期清理与心跳竞争后，最后一次心跳必须仍可恢复在线。
                try (var workers = Executors.newFixedThreadPool(2)) {
                    for (int i = 0; i < 8; i++) {
                        sql.update("UPDATE presence SET last_seen_at = ? WHERE user_id = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(61)), second);
                        var gate = new CountDownLatch(1);
                        var heartbeat = workers.submit(() -> { gate.await(); social.heartbeat(second); return null; });
                        var expiry = workers.submit(() -> { gate.await(); social.expirePresence(); return null; });
                        gate.countDown(); heartbeat.get(5, TimeUnit.SECONDS); expiry.get(5, TimeUnit.SECONDS);
                        assertThat(social.listFriends(first)).extracting(FriendView::presence).containsExactly("ONLINE");
                    }
                }
                var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                long beforeRollback = outbox.latestSequence();
                assertThatThrownBy(() -> transaction.execute(status -> {
                    social.deleteFriend(first, second); throw new IllegalStateException("rollback");
                })).isInstanceOf(IllegalStateException.class);
                assertThat(outbox.latestSequence()).isEqualTo(beforeRollback);
                assertThat(social.listFriends(first)).hasSize(1);
                cursor = outbox.latestSequence(); social.deleteFriend(first, second);
                assertPrivate(sync, first, cursor, 1); assertPrivate(sync, second, cursor, 1);
                assertPrivate(sync, outsider, cursor, 0);
                assertThat(social.listFriends(first)).isEmpty(); assertThat(social.listFriends(second)).isEmpty();
                cursor = outbox.latestSequence();
                var rejected = social.sendRequest(first, second); social.reject(second, rejected.id());
                assertPrivate(sync, first, cursor, 2); assertPrivate(sync, second, cursor, 2);
                cursor = outbox.latestSequence();
                sql.update("UPDATE presence SET last_seen_at = ? WHERE user_id = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(61)), second);
                social.expirePresence(); social.heartbeat(second);
                assertPrivate(sync, first, cursor, 0);
                assertPrivate(sync, outsider, cursor, 0);
            } finally { socket.abort(); }
        }
    }

    @Test void schedulerExpiresPresenceAndNotifiesFriendsWithoutClientRequests() throws Exception {
        try (var postgres = EmbeddedPostgres.start(); var context = new SpringApplication(ControlPlaneApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identities = context.getBean(IdentityRepository.class);
            String first = user(identities), second = user(identities);
            var social = context.getBean(SocialService.class);
            var outbox = context.getBean(OutboxRepository.class);
            var sql = context.getBean(JdbcTemplate.class);
            social.accept(second, social.sendRequest(first, second).id()); social.heartbeat(second);
            long cursor = outbox.latestSequence();
            sql.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", second);
            assertThat(social.listFriends(first)).extracting(FriendView::presence).containsExactly("OFFLINE");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            var sync = context.getBean(SyncService.class);
            while (sync.history(first, cursor).isEmpty() && System.nanoTime() < deadline) Thread.sleep(20);
            assertPrivate(sync, first, cursor, 1);
            assertThat(sql.queryForObject("SELECT count(*) FROM presence WHERE user_id = ?", Integer.class, second)).isZero();
            assertPrivate(sync, second, cursor, 0);
        }
    }

    private static String user(IdentityRepository identities) {
        String id = UUID.randomUUID().toString();
        identities.insertUser(new UserAccount(id, id + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
        return id;
    }
    private static void assertPrivate(SyncService sync, String user, long cursor, int count) {
        assertThat(sync.history(user, cursor)).hasSize(count).allMatch(event -> event.type().equals("user.changed")
                && event.groupId() == null && event.activityId() == null && event.payload().equals("{}"));
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
        void awaitCount(int expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (events.size() < expected && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(events).hasSize(expected);
        }
    }
}
