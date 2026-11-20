package app.weavora.zhijie.controlplane.activity;

import app.weavora.zhijie.controlplane.identity.IdentityService;
import app.weavora.zhijie.controlplane.identity.AuthSession;
import app.weavora.zhijie.controlplane.common.ApiException;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ActivityController {
    private final ActivityCoordinator activities;
    private final IdentityService identity;
    private final ActivityModerationService moderation;

    public ActivityController(ActivityCoordinator activities, IdentityService identity, ActivityModerationService moderation) {
        this.activities = activities;
        this.identity = identity;
        this.moderation = moderation;
    }

    @PostMapping("/room-joins")
    public Map<String, JoinResult> join(@RequestHeader("Authorization") String auth, @Valid @RequestBody JoinBody body) {
        var session = deviceSession(auth, body.deviceId());
        return Map.of("data", activities.join(session, body.roomId(), body.password(), body.operationId()));
    }

    @PostMapping("/activities/{activityId}/resume")
    public Map<String, JoinResult> resume(@RequestHeader("Authorization") String auth, @PathVariable String activityId,
                                          @Valid @RequestBody DeviceBody body) {
        var session = deviceSession(auth, body.deviceId());
        return Map.of("data", activities.resume(session, activityId));
    }

    @PostMapping("/activities/{activityId}/leave")
    public Map<String, Object> leave(@RequestHeader("Authorization") String auth, @PathVariable String activityId,
                                    @Valid @RequestBody LeaveBody body) {
        var session = identity.authenticateSession(auth);
        boolean left = activities.leave(session, activityId, body.leaseId());
        return Map.of("data", Map.of("left", left));
    }

    @PostMapping("/activities/{activityId}/heartbeat")
    public Map<String, Object> heartbeat(@RequestHeader("Authorization") String auth, @PathVariable String activityId,
                                         @Valid @RequestBody HeartbeatBody body) {
        var session = deviceSession(auth, body.deviceId());
        activities.heartbeat(session, activityId, body.leaseId(), body.activityGeneration(), body.memberGeneration());
        return Map.of("data", Map.of("renewed", true));
    }

    @GetMapping("/activities/{activityId}/members")
    public Map<String, List<ActivityMember>> members(@RequestHeader("Authorization") String auth, @PathVariable String activityId) {
        return Map.of("data", activities.listMembers(actor(auth), activityId));
    }

    private String actor(String authorization) { return identity.authenticate(authorization).id(); }

    private AuthSession deviceSession(String authorization, String deviceId) {
        var session = identity.authenticateSession(authorization);
        if (!session.deviceId().equals(deviceId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CURRENT_GRANT_REQUIRED", "请求设备与登录会话不符", false);
        }
        return session;
    }

    @PostMapping("/activities/{activityId}/members/{userId}/kick")
    public Map<String, Object> kick(@RequestHeader("Authorization") String auth, @PathVariable String activityId,
            @PathVariable String userId, @Valid @RequestBody KickBody body) {
        moderation.kick(actor(auth), activityId, userId, body.memberGeneration());
        return Map.of("data", Map.of("removed", true));
    }

    public record KickBody(@Positive long memberGeneration) {
    }

    public record JoinBody(@NotBlank String roomId, @NotBlank String deviceId, String password, @NotBlank String operationId) {
    }
    public record DeviceBody(@NotBlank String deviceId) {
    }
    public record LeaveBody(@NotBlank String leaseId) {
    }
    public record HeartbeatBody(@NotBlank String deviceId, @NotBlank String leaseId,
                                @Positive long activityGeneration, @Positive long memberGeneration) {
    }
}
