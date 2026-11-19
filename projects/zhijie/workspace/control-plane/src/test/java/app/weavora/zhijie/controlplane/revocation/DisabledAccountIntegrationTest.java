package app.weavora.zhijie.controlplane.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.weavora.zhijie.controlplane.ControlPlaneApplication;
import app.weavora.zhijie.controlplane.activity.*;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.*;
import app.weavora.zhijie.controlplane.group.*;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.network.NetworkAccessService;
import app.weavora.zhijie.controlplane.room.*;
import app.weavora.zhijie.controlplane.sync.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;

class DisabledAccountIntegrationTest {
    @Test
    void realSchedulerRevokesWithoutAnyRequestFromTheDisabledClient() throws Exception {
        var application = new SpringApplication(ControlPlaneApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var postgres = EmbeddedPostgres.start(); var context = application.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            var identities = context.getBean(IdentityRepository.class);
            var groups = context.getBean(GroupService.class);
            var rooms = context.getBean(RoomService.class);
            String owner = UUID.randomUUID().toString(), member = UUID.randomUUID().toString();
            for (String user : List.of(owner, member)) identities.insertUser(new UserAccount(user, user + "@example.test", AccountStatus.ACTIVE, "unused", Instant.now()));
            Group group = groups.createGroup(owner, "定时失效", null, "");
            groups.acceptInvitation(member, groups.invite(owner, group.id(), member).id());
            Room room = rooms.createRoom(owner, group.id(), "定时清理房", "", "", null);
            rooms.assignOwner(owner, room.id(), member);
            Activity activity = rooms.startActivity(owner, room.id());
            context.getBean(ActivityCoordinator.class).join(member, room.id(), "scheduled-device", null, "scheduled-join");
            context.getBean(JdbcTemplate.class).update("UPDATE users SET status = 'DISABLED' WHERE id = ?", member);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            var activities = context.getBean(ActivityRepository.class);
            while (activities.findMember(activity.id(), member).orElseThrow().state() != MemberState.REVOKED && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(activities.findMember(activity.id(), member).orElseThrow().state()).isEqualTo(MemberState.REVOKED);
            assertThat(activities.findAccountActivity(member)).isEmpty();
            assertThat(context.getBean(RoomRepository.class).findRoom(room.id()).orElseThrow().ownerUserId()).isEqualTo(owner);
        }
    }

    @Test
    void explicitDisabledStatusRevokesSessionsActivitiesAndOfflineRoomOwnershipWithoutPromotingAGroupOwner() throws Exception {
        var application = new SpringApplication(ControlPlaneApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var postgres = EmbeddedPostgres.start(); var context = application.run(
                "--zhijie.scheduling.enabled=false", "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--zhijie.voice.url=wss://voice.example.test", "--zhijie.voice.enabled=true", "--zhijie.voice.api-key=disabled-test",
                "--zhijie.voice.api-secret=controlled-disabled-account-secret-123456789")) {
            var identities = context.getBean(IdentityRepository.class);
            var identity = context.getBean(IdentityService.class);
            var groups = context.getBean(GroupService.class);
            var rooms = context.getBean(RoomService.class);
            var roomRepository = context.getBean(RoomRepository.class);
            var coordinator = context.getBean(ActivityCoordinator.class);
            var activities = context.getBean(ActivityRepository.class);
            var sql = context.getBean(JdbcTemplate.class);
            var scheduler = context.getBean(DisabledAccountScheduler.class);
            var mapper = context.getBean(DisabledAccountMapper.class);
            String hash = context.getBean(CredentialProtector.class).hashPassword("disabled-test-password-123");
            var users = new ArrayList<String>(); var sessions = new ArrayList<AuthTokens>();
            for (int i = 0; i < 5; i++) {
                String id = UUID.randomUUID().toString(), email = "disabled-" + i + "@example.test";
                identities.insertUser(new UserAccount(id, email, AccountStatus.ACTIVE, hash, Instant.now()));
                users.add(id); sessions.add(identity.login(email, "disabled-test-password-123", "login-device"));
            }
            String owner = users.get(0), disabled = users.get(1), active = users.get(2), invitee = users.get(3), sessionOnly = users.get(4);
            Group group = groups.createGroup(owner, "失效清理", null, "");
            for (String user : List.of(disabled, active)) groups.acceptInvitation(user, groups.invite(owner, group.id(), user).id());
            Room room = rooms.createRoom(owner, group.id(), "活动房", "", "", null);
            Room offline = rooms.createRoom(owner, group.id(), "离线房主", "", "", null);
            rooms.assignOwner(owner, room.id(), disabled);
            rooms.assignOwner(owner, offline.id(), disabled);
            Activity activity = rooms.startActivity(owner, room.id());
            coordinator.join(disabled, room.id(), "disabled-device", null, "disabled-join");
            coordinator.join(active, room.id(), "active-device", null, "active-join");
            coordinator.disconnect(disabled, activity.id(), "disabled-device");
            GroupInvitation pending = groups.invite(disabled, group.id(), invitee);
            OwnerTransfer transfer = groups.initiateTransfer(owner, group.id(), disabled);
            byte[] key = new byte[32]; Arrays.fill(key, (byte) 3);
            var grants = context.getBean(GrantService.class);
            var network = grants.network(disabled, activity.id(), Base64.getEncoder().encodeToString(key));
            var voice = grants.voice(disabled, activity.id());

            // 凭据过期和普通离线不改变房主；只有明确失效进入清理候选。
            scheduler.reconcile();
            assertThat(roomRepository.findRoom(offline.id()).orElseThrow().ownerUserId()).isEqualTo(disabled);
            sql.update("UPDATE users SET status = 'DISABLED' WHERE id IN (?, ?)", disabled, sessionOnly);
            assertThatThrownBy(() -> identity.authenticate("Bearer " + sessions.get(1).accessToken())).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> context.getBean(NetworkAccessService.class).validate(network.networkTicket())).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> context.getBean(VoiceAccessValidator.class).validate(voice.voiceToken())).isInstanceOf(ApiException.class);
            scheduler.reconcile();
            assertThat(activities.findMember(activity.id(), disabled).orElseThrow().state()).isEqualTo(MemberState.REVOKED);
            assertThat(activities.findLatestLease(activity.id(), disabled).orElseThrow().revokedAt()).isNotNull();
            assertThat(activities.findAccountActivity(disabled)).isEmpty();
            assertThat(activities.findAddress(activity.id(), disabled).orElseThrow().releasedAt()).isNotNull();
            assertThat(context.getBean(GrantRepository.class).findById(network.grantId()).orElseThrow().revokedAt()).isNotNull();
            assertThat(context.getBean(GrantRepository.class).findById(voice.grantId()).orElseThrow().revokedAt()).isNotNull();
            assertThat(identities.findSessionById(sessions.get(1).sessionId()).orElseThrow().revokedAt()).isNotNull();
            assertThat(identities.findSessionById(sessions.get(4).sessionId()).orElseThrow().revokedAt()).isNotNull();
            assertThat(roomRepository.findRoom(room.id()).orElseThrow().ownerUserId()).isEqualTo(owner);
            assertThat(roomRepository.findRoom(offline.id()).orElseThrow().ownerUserId()).isEqualTo(owner);
            assertThat(context.getBean(GroupRepository.class).findInvitation(pending.id()).orElseThrow().status()).isEqualTo(InvitationStatus.INVALIDATED);
            assertThat(context.getBean(GroupRepository.class).findPendingTransferById(transfer.id())).isEmpty();
            assertThat(activities.findMember(activity.id(), active).orElseThrow().state()).isEqualTo(MemberState.ACTIVE);
            assertThat(context.getBean(SyncService.class).history(disabled, 0)).isEmpty();
            int count = sql.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND type IN ('voice.revoked', 'network.revoked', 'lease.revoked')", Integer.class, disabled);
            assertThat(count).isEqualTo(3);
            scheduler.reconcile();
            assertThat(sql.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND type IN ('voice.revoked', 'network.revoked', 'lease.revoked')", Integer.class, disabled)).isEqualTo(count);
            assertThat(mapper.candidates()).isEmpty();

            // 账号状态在扫描后变化时，执行前重读，迟到清理不得撤销有效账号。
            context.getBean(DisabledAccountRevocation.class).revoke(new DisabledAccountMapper.Candidate(group.id(), active));
            assertThat(activities.findMember(activity.id(), active).orElseThrow().state()).isEqualTo(MemberState.ACTIVE);
            // 群主账号失效清退其活动，但不能擅自替换群主或阻断其他失效房主接管。
            coordinator.join(owner, room.id(), "owner-device", null, "owner-join");
            rooms.assignOwner(owner, offline.id(), active);
            sql.update("UPDATE users SET status = 'DISABLED' WHERE id IN (?, ?)", owner, active);
            scheduler.reconcile();
            assertThat(activities.findMember(activity.id(), owner).orElseThrow().state()).isEqualTo(MemberState.REVOKED);
            assertThat(activities.findMember(activity.id(), active).orElseThrow().state()).isEqualTo(MemberState.REVOKED);
            assertThat(context.getBean(GroupRepository.class).findGroup(group.id()).orElseThrow().ownerUserId()).isEqualTo(owner);
            assertThat(context.getBean(GroupRepository.class).findMember(group.id(), owner).orElseThrow().role()).isEqualTo(GroupRole.OWNER);
            assertThat(roomRepository.findRoom(offline.id()).orElseThrow().ownerUserId()).isEqualTo(owner);
            scheduler.reconcile();
            assertThat(mapper.candidates()).isEmpty();
        }
    }
}
