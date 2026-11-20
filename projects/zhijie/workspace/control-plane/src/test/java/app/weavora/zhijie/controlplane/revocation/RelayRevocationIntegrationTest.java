package app.weavora.zhijie.controlplane.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.AccountStatus;
import app.weavora.zhijie.controlplane.identity.IdentityRepository;
import app.weavora.zhijie.controlplane.identity.UserAccount;
import app.weavora.zhijie.controlplane.room.RoomService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;

class RelayRevocationIntegrationTest {
    @Test void configuredRelayReceivesDurableScopedRevocationAndOnlyNetworkEventCompletes() throws Exception {
        String key = UUID.randomUUID().toString();
        var command = new AtomicReference<JsonNode>();
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/relay/v1/revocations", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Relay-Revocation-Key")).isEqualTo(key);
            var body = json.readTree(exchange.getRequestBody());
            command.set(body);
            byte[] response = json.writeValueAsBytes(java.util.Map.of(
                    "version", 1, "eventId", body.required("eventId").asText(), "status", "applied"));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var postgres = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.start();
             var context = new SpringApplication(ControlPlaneApplication.class).run(
                     "--server.address=127.0.0.1", "--server.port=0", "--zhijie.scheduling.enabled=false",
                     "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                     "--spring.datasource.username=postgres", "--spring.datasource.password=",
                     "--zhijie.relay.revocation-enabled=true", "--zhijie.relay.revocation-key=" + key,
                     "--zhijie.relay.admin-url=http://127.0.0.1:" + server.getAddress().getPort())) {
            String user = UUID.randomUUID().toString();
            context.getBean(IdentityRepository.class).insertUser(new UserAccount(user, user + "@example.test",
                    AccountStatus.ACTIVE, "unused", Instant.now()));
            var group = context.getBean(GroupService.class).createGroup(user, "Relay 撤销", null, "");
            var rooms = context.getBean(RoomService.class);
            var room = rooms.createRoom(user, group.id(), "撤销房间", "", "", null);
            String activity = rooms.startActivity(user, room.id()).id();
            var coordinator = context.getBean(ActivityCoordinator.class);
            var joined = coordinator.join(user, room.id(), "device", null, "join");
            var dispatcher = context.getBean(RevocationDispatcher.class);
            dispatcher.dispatchPending();
            coordinator.leave(user, activity, joined.deviceId(), joined.leaseId());
            dispatcher.dispatchPending();
            var sql = context.getBean(JdbcTemplate.class);
            assertThat(sql.queryForObject("SELECT delivered_at IS NOT NULL FROM outbox_events WHERE type='network.revoked'", Boolean.class)).isTrue();
            assertThat(sql.queryForObject("SELECT delivered_at IS NULL FROM outbox_events WHERE type='voice.revoked'", Boolean.class)).isTrue();
            assertThat(command.get()).isNotNull();
            assertThat(command.get().required("version").asInt()).isEqualTo(1);
            assertThat(command.get().required("activityId").asText()).isEqualTo(activity);
            assertThat(command.get().required("userId").asText()).isEqualTo(user);
            assertThat(command.get().required("memberGeneration").asLong()).isEqualTo(joined.memberGeneration());
            assertThat(command.get().toString()).doesNotContain(key, "ticket", "device");
        } finally { server.stop(0); }
    }
}
