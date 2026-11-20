package app.weavora.zhijie.controlplane.grant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.weavora.zhijie.controlplane.activity.*;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.identity.*;
import app.weavora.zhijie.controlplane.room.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VoiceAccessValidatorTest {
    private final Instant now = Instant.parse("2026-11-17T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();
    private final String secret = "controlled-voice-secret-at-least-32-characters";
    private final ActivityRepository activities = mock(ActivityRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final IdentityRepository identities = mock(IdentityRepository.class);
    private final app.weavora.zhijie.controlplane.group.GroupService groups = mock(app.weavora.zhijie.controlplane.group.GroupService.class);
    private VoiceAccessValidator validator;
    private String token;

    @BeforeEach
    void setup() {
        validator = new VoiceAccessValidator("key", secret, activities, rooms, identities, json, clock, groups);
        token = new LiveKitVoiceGateway("key", secret, "http://127.0.0.1:7880", json, clock)
                .issueToken("activity", "user", 7, now.plusSeconds(60));
        when(identities.findUserById("user")).thenReturn(Optional.of(new UserAccount("user", "a@example.test", AccountStatus.ACTIVE, "unused", now)));
        when(rooms.findActivity("activity")).thenReturn(Optional.of(new Activity("activity", "room", 2L, ActivityState.ACTIVE, now, null)));
        when(rooms.findRoom("room")).thenReturn(Optional.of(new Room("room", "group", "房间", "", "", null, "user", RoomOpenState.OPEN, RoomLifecycle.AVAILABLE, "activity")));
        member(MemberState.ACTIVE, 7);
        lease(null, now.plusSeconds(60));
        when(activities.findAccountActivity("user")).thenReturn(Optional.of(new AccountActivity("user", "activity", "device", 7L, "join", "session")));
        when(identities.findSessionById("session")).thenReturn(Optional.of(new AuthSession("session", "user", "device", "refresh", "access",
                now.plusSeconds(3600), now.plusSeconds(30), null, 1L)));
    }

    @Test
    void validSignatureRequiresCurrentMemberLeaseDeviceAndActivity() {
        validator.validate(token);
        member(MemberState.GRACE, 7);
        validator.validate(token);
        member(MemberState.REVOKED, 7);
        denied(token);
        member(MemberState.ACTIVE, 8);
        denied(token);
        member(MemberState.ACTIVE, 7);
        lease(now, now.plusSeconds(60));
        denied(token);
        lease(null, now.minusSeconds(1));
        denied(token);
        lease(null, now.plusSeconds(60));
        when(activities.findAccountActivity("user")).thenReturn(Optional.of(new AccountActivity("user", "other-activity", "device", 7L, "switch", "session")));
        denied(token);
    }

    @Test
    void boundLoginRevocationExpiryAndDeviceMismatchDenyTokensWithoutWaitingForLeaseCleanup() {
        for (var session : java.util.List.of(
                new AuthSession("session", "user", "device", "refresh", "access", now.plusSeconds(60), now.plusSeconds(30), now, 1L),
                new AuthSession("session", "user", "device", "refresh", "access", now, now.plusSeconds(30), null, 1L),
                new AuthSession("session", "other", "device", "refresh", "access", now.plusSeconds(60), now.plusSeconds(30), null, 1L),
                new AuthSession("session", "user", "other-device", "refresh", "access", now.plusSeconds(60), now.plusSeconds(30), null, 1L))) {
            when(identities.findSessionById("session")).thenReturn(Optional.of(session));
            denied(token);
        }
        when(identities.findSessionById("session")).thenReturn(Optional.empty());
        denied(token);
        when(activities.findAccountActivity("user")).thenReturn(Optional.of(new AccountActivity("user", "activity", "device", 7L, "join", null)));
        denied(token);
    }

    @Test
    void endedActivityDisabledAccountAndForgedOrExpiredTokenAreDenied() {
        denied(token.substring(0, token.length() - 4) + "AAAA");
        denied("malformed-token");
        denied("x".repeat(8193));
        assertThatThrownBy(() -> new VoiceAccessValidator("key", secret, activities, rooms, identities, json,
                Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC), groups).validate(token)).isInstanceOf(ApiException.class);
        when(rooms.findActivity("activity")).thenReturn(Optional.of(new Activity("activity", "room", 2L, ActivityState.ENDED, now, now)));
        denied(token);
        when(rooms.findActivity("activity")).thenReturn(Optional.of(new Activity("activity", "room", 2L, ActivityState.ACTIVE, now, null)));
        when(identities.findUserById("user")).thenReturn(Optional.of(new UserAccount("user", "a@example.test", AccountStatus.DISABLED, "unused", now)));
        denied(token);
    }

    @Test
    void signalingManagementKeyIsRequiredAndClientCannotSubstituteTokenHeader() {
        var controller = new VoiceAccessController(validator, "controlled-management-key-at-least-32-chars");
        assertThatThrownBy(() -> controller.authorize("wrong", "Bearer " + token, "")).isInstanceOf(ApiException.class);
        controller.authorize("controlled-management-key-at-least-32-chars", "Bearer " + token, "invalid");
        controller.authorize("controlled-management-key-at-least-32-chars", "", token);
        assertThatThrownBy(() -> new VoiceAccessController(validator, "").authorize("", "Bearer " + token, "")).isInstanceOf(ApiException.class);
    }

    private void member(MemberState state, long generation) {
        when(activities.findMember("activity", "user")).thenReturn(Optional.of(new ActivityMember("activity", "user", "device", generation, state, now, null)));
    }

    private void lease(Instant revokedAt, Instant expiresAt) {
        when(activities.findLatestLease("activity", "user")).thenReturn(Optional.of(new ConnectionLease("lease", "activity", "user", "device", 2L, 7L, 1L,
                ChannelState.CONNECTED, ChannelState.CONNECTED, expiresAt, revokedAt)));
    }

    private void denied(String value) {
        assertThatThrownBy(() -> validator.validate(value)).isInstanceOfSatisfying(ApiException.class, error -> {
            org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("VOICE_ACCESS_DENIED");
            org.assertj.core.api.Assertions.assertThat(error.getCause()).isNull();
        });
    }
}
