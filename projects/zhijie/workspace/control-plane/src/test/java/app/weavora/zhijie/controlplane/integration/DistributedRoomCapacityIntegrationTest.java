package app.weavora.zhijie.controlplane.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.group.GroupService;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.room.RoomService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "ZHIJIE_TEST_DISTRIBUTED_ROOMS", matches = "true")
class DistributedRoomCapacityIntegrationTest {
    private record Account(String user, String device, String bearer) {}
    private record Room(String id, String activity) {}
    private record Seat(Account account, Room room) {}
    private record Grant(Seat seat, String lease, long activityGeneration, long memberGeneration) {}

    private record Api(HttpClient http, String base, ObjectMapper json) {
        JsonNode request(Account account, String path, Map<String, ?> body, int status) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + account.bearer());
            if (body != null) builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            var result = json.readTree(response.body());
            assertThat(response.statusCode()).as("%s (%s)", path, result.at("/error/code").asText()).isEqualTo(status);
            return result;
        }

        JsonNode joinRequest(Seat seat, int status) throws Exception {
            return request(seat.account(), "/room-joins", Map.of("roomId", seat.room().id(),
                    "deviceId", seat.account().device(), "operationId", UUID.randomUUID().toString()), status);
        }

        Grant join(Seat seat) throws Exception {
            var data = joinRequest(seat, 200).required("data");
            assertThat(data.required("activityId").asText()).isEqualTo(seat.room().activity());
            assertThat(data.required("deviceId").asText()).isEqualTo(seat.account().device());
            String lease = data.required("leaseId").textValue();
            assertThat(lease).isNotBlank();
            return new Grant(seat, lease, data.required("activityGeneration").longValue(), data.required("memberGeneration").longValue());
        }

        boolean heartbeat(Grant grant) throws Exception {
            var seat = grant.seat();
            return request(seat.account(), "/activities/" + seat.room().activity() + "/heartbeat", Map.of(
                    "deviceId", seat.account().device(), "leaseId", grant.lease(), "activityGeneration", grant.activityGeneration(),
                    "memberGeneration", grant.memberGeneration()), 200).at("/data/renewed").asBoolean();
        }

        boolean leave(Grant grant) throws Exception {
            var seat = grant.seat();
            return request(seat.account(), "/activities/" + seat.room().activity() + "/leave",
                    Map.of("leaseId", grant.lease()), 200).at("/data/left").asBoolean();
        }
    }

    @Test @Timeout(180)
    void hundredAccountsHoldNineRoomsAndSwitchOnlyAfterCapacityIsAvailable() throws Exception {
        try (var postgres = EmbeddedPostgres.start();
             var context = new SpringApplication(ControlPlaneApplication.class).run("--server.address=127.0.0.1", "--server.port=0",
                     "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"), "--spring.datasource.username=postgres",
                     "--spring.datasource.password=");
             var pool = Executors.newFixedThreadPool(12);
             var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var api = new Api(http, "http://127.0.0.1:" + port + "/api/v1", context.getBean(ObjectMapper.class));
            var db = context.getBean(JdbcTemplate.class);
            var seats = seed(context.getBean(IdentityRepository.class), context.getBean(CredentialProtector.class),
                    context.getBean(GroupService.class), context.getBean(RoomService.class));
            var grants = new ArrayList<Grant>();
            for (int offset = 0; offset < seats.size(); offset += 20) {
                if (offset > 0) Thread.sleep(Duration.ofSeconds(1));
                grants.addAll(wave(pool, seats.subList(offset, Math.min(offset + 20, seats.size())), api::join));
            }
            assertThat(grants).hasSize(100);
            assertThat(wave(pool, grants, api::heartbeat)).containsOnly(true);
            verifyDistribution(api, db, grants);

            var full = grants.get(0);
            var partial = grants.get(8);
            var switchSeat = new Seat(partial.seat().account(), full.seat().room());
            assertThat(api.joinRequest(switchSeat, 409).at("/error/code").asText()).isEqualTo("CAPACITY_FULL");
            assertThat(api.heartbeat(partial)).isTrue();
            assertThat(api.leave(full)).isTrue();
            grants.set(8, api.join(switchSeat));
            grants.set(0, api.join(new Seat(full.seat().account(), partial.seat().room())));
            verifyDistribution(api, db, grants);
            assertThat(wave(pool, grants, api::heartbeat)).containsOnly(true);
            assertThat(wave(pool, grants, api::leave)).containsOnly(true);
            for (String query : List.of("select count(*) from account_activity", "select count(*) from connection_leases where revoked_at is null",
                    "select count(*) from ip_allocations where released_at is null", "select count(*) from activity_members where state in ('ACTIVE','GRACE')")) {
                assertThat(db.queryForObject(query, Integer.class)).as(query).isZero();
            }
        }
    }

    private List<Seat> seed(IdentityRepository identities, CredentialProtector credentials, GroupService groups, RoomService rooms) {
        var accounts = new ArrayList<Account>();
        for (int index = 0; index < 100; index++) {
            String user = UUID.randomUUID().toString(), session = UUID.randomUUID().toString();
            String device = "distributed-" + index, secret = credentials.randomSecret();
            Instant now = Instant.now(), expires = now.plusSeconds(3600);
            identities.insertUser(new UserAccount(user, user + "@capacity.example.test", AccountStatus.ACTIVE, "unused", now));
            identities.insertSession(new AuthSession(session, user, device, credentials.hashToken(credentials.randomSecret()),
                    credentials.hashToken(secret), expires, expires, null, 1L));
            accounts.add(new Account(user, device, session + "." + secret));
        }
        String owner = accounts.getFirst().user();
        var group = groups.createGroup(owner, "distributed rooms", null, "");
        for (var account : accounts.subList(1, accounts.size())) {
            groups.acceptInvitation(account.user(), groups.invite(owner, group.id(), account.user()).id());
        }
        var destinations = new ArrayList<Room>();
        for (int index = 0; index < 9; index++) {
            var room = rooms.createRoom(owner, group.id(), "room-" + index, "", "", null);
            destinations.add(new Room(room.id(), rooms.startActivity(owner, room.id()).id()));
        }
        var seats = new ArrayList<Seat>();
        for (int position = 0; position < 12; position++) {
            for (var room : destinations.subList(0, position < 4 ? 9 : 8)) {
                seats.add(new Seat(accounts.get(seats.size()), room));
            }
        }
        return seats;
    }

    private void verifyDistribution(Api api, JdbcTemplate db, List<Grant> grants) throws Exception {
        var expectedRooms = new LinkedHashMap<Room, List<Grant>>();
        grants.forEach(grant -> expectedRooms.computeIfAbsent(grant.seat().room(), ignored -> new ArrayList<>()).add(grant));
        assertThat(expectedRooms.values().stream().map(List::size).sorted().toList()).containsExactly(4, 12, 12, 12, 12, 12, 12, 12, 12);
        for (var entry : expectedRooms.entrySet()) {
            var activity = entry.getKey().activity();
            var expected = entry.getValue();
            var members = api.request(expected.getFirst().seat().account(), "/activities/" + activity + "/members", null, 200).required("data");
            var users = new ArrayList<String>();
            for (var member : members) {
                assertThat(member.required("state").asText()).isEqualTo("ACTIVE");
                users.add(member.required("userId").asText());
            }
            var expectedUsers = expected.stream().map(grant -> grant.seat().account().user()).toList();
            assertThat(users).containsExactlyInAnyOrderElementsOf(expectedUsers);
            assertThat(db.queryForList("select user_id from account_activity where activity_id=?", String.class, activity))
                    .containsExactlyInAnyOrderElementsOf(expectedUsers);
            assertThat(db.queryForList("select id from connection_leases where activity_id=? and revoked_at is null and expires_at>now()", String.class, activity))
                    .containsExactlyInAnyOrderElementsOf(expected.stream().map(Grant::lease).toList());
            assertThat(db.queryForList("select user_id from ip_allocations where activity_id=? and released_at is null", String.class, activity))
                    .containsExactlyInAnyOrderElementsOf(expectedUsers);
            assertThat(db.queryForObject("select count(distinct ipv4) from ip_allocations where activity_id=? and released_at is null", Integer.class, activity))
                    .isEqualTo(expected.size());
        }
    }

    private interface Operation<Input, Output> { Output apply(Input input) throws Exception; }

    private <Input, Output> List<Output> wave(ExecutorService pool, List<Input> inputs, Operation<Input, Output> operation) throws Exception {
        var tasks = inputs.stream().<Callable<Output>>map(input -> () -> operation.apply(input)).toList();
        var results = new ArrayList<Output>();
        for (var future : pool.invokeAll(tasks, 40, TimeUnit.SECONDS)) results.add(future.get());
        return results;
    }
}
