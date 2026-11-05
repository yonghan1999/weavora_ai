package app.weavora.zhijie.controlplane.activity;

import app.weavora.zhijie.controlplane.identity.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    public ActivityController(ActivityCoordinator activities, IdentityService identity) {
        this.activities = activities;
        this.identity = identity;
    }

    @PostMapping("/room-joins")
    public Map<String, JoinResult> join(@RequestHeader("Authorization") String auth, @Valid @RequestBody JoinBody body) {
        return Map.of("data", activities.join(actor(auth), body.roomId(), body.deviceId(), body.password(), body.operationId()));
    }

    @PostMapping("/activities/{activityId}/resume")
    public Map<String, JoinResult> resume(@RequestHeader("Authorization") String auth, @PathVariable String activityId,
                                          @Valid @RequestBody DeviceBody body) {
        return Map.of("data", activities.resume(actor(auth), activityId, body.deviceId()));
    }

    @PostMapping("/activities/{activityId}/leave")
    public Map<String, Object> leave(@RequestHeader("Authorization") String auth, @PathVariable String activityId) {
        activities.leave(actor(auth), activityId);
        return Map.of("data", Map.of("left", true));
    }

    @GetMapping("/activities/{activityId}/members")
    public Map<String, List<ActivityMember>> members(@RequestHeader("Authorization") String auth, @PathVariable String activityId) {
        return Map.of("data", activities.listMembers(actor(auth), activityId));
    }

    private String actor(String authorization) { return identity.authenticate(authorization).id(); }

    public record JoinBody(@NotBlank String roomId, @NotBlank String deviceId, String password, @NotBlank String operationId) {
    }
    public record DeviceBody(@NotBlank String deviceId) {
    }
}
