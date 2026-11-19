package app.weavora.zhijie.controlplane.observability;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.GrantService;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.AccountStatus;
import app.weavora.zhijie.controlplane.identity.IdentityRepository;
import app.weavora.zhijie.controlplane.identity.UserAccount;
import app.weavora.zhijie.controlplane.network.NetworkAccessService;
import app.weavora.zhijie.controlplane.room.RoomService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

class ChannelCapacityIntegrationTest {
    record Member(String user, String activity) {}

    @Test void renewalDoesNotUseAnotherSlotAndCompetingRoomsCannotOverbook() throws Exception {
        try (var postgres = EmbeddedPostgres.start();
             var context = SpringApplication.run(ControlPlaneApplication.class, "--server.address=127.0.0.1", "--server.port=0",
                     "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"), "--spring.datasource.username=postgres",
                     "--spring.datasource.password=", "--zhijie.scheduling.enabled=false", "--zhijie.capacity.network=2");
             var workers = Executors.newFixedThreadPool(2)) {
            var rooms = context.getBean(RoomService.class);
            var coordinator = context.getBean(ActivityCoordinator.class);
            var grants = context.getBean(GrantService.class);
            var members = new ArrayList<Member>();
            for (int index = 0; index < 3; index++) {
                String user = UUID.randomUUID().toString();
                context.getBean(IdentityRepository.class).insertUser(new UserAccount(user, user + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
                var group = context.getBean(GroupService.class).createGroup(user, "容量验证", null, "");
                var room = rooms.createRoom(user, group.id(), "容量验证", "", "", null);
                var activity = rooms.startActivity(user, room.id());
                coordinator.join(user, room.id(), "device-" + index, null, "join-" + index);
                members.add(new Member(user, activity.id()));
            }
            var incumbent = members.removeFirst();
            byte[] bytes = new byte[32];
            bytes[0] = 1;
            String firstKey = Base64.getEncoder().encodeToString(bytes);
            var first = grants.network(incumbent.user(), incumbent.activity(), firstKey);
            grants.network(incumbent.user(), incumbent.activity(), firstKey);
            var start = new CyclicBarrier(2);
            List<Future<Boolean>> results = new ArrayList<>();
            for (var member : members) {
                results.add(workers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        grants.network(member.user(), member.activity(), firstKey);
                        return true;
                    } catch (ApiException denied) {
                        assertThat(denied.code()).isEqualTo("NETWORK_CAPACITY_LIMITED");
                        return false;
                    }
                }));
            }
            boolean firstAdmitted = results.getFirst().get(10, TimeUnit.SECONDS);
            assertThat(results.getLast().get(10, TimeUnit.SECONDS)).isNotEqualTo(firstAdmitted);
            var access = context.getBean(NetworkAccessService.class);
            var overlap = grants.network(incumbent.user(), incumbent.activity(), firstKey);
            assertThat(access.validate(first.networkTicket()).lease().id()).isEqualTo(overlap.leaseId());
            bytes[0] = 2;
            var rotated = grants.network(incumbent.user(), incumbent.activity(), Base64.getEncoder().encodeToString(bytes));
            assertThat(access.validate(rotated.networkTicket()).lease().id()).isEqualTo(first.leaseId());
            var winner = members.get(firstAdmitted ? 0 : 1);
            var waiting = members.get(firstAdmitted ? 1 : 0);
            coordinator.leave(winner.user(), winner.activity());
            assertThat(grants.network(waiting.user(), waiting.activity(), firstKey).grantId()).isNotBlank();
        }
    }
}
