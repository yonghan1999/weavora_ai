package app.weavora.zhijie.controlplane.grant;

import app.weavora.zhijie.controlplane.identity.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GrantController {
    private final GrantService grants;
    private final IdentityService identity;

    public GrantController(GrantService grants, IdentityService identity) {
        this.grants = grants;
        this.identity = identity;
    }

    @PostMapping("/activities/{activityId}/voice-grants")
    public Map<String, VoiceGrantResponse> voice(@RequestHeader("Authorization") String auth, @PathVariable String activityId) {
        return Map.of("data", grants.voice(identity.authenticate(auth).id(), activityId));
    }

    @PostMapping("/activities/{activityId}/network-grants")
    public Map<String, NetworkGrantResponse> network(@RequestHeader("Authorization") String auth, @PathVariable String activityId,
                                                     @Valid @RequestBody NetworkBody body) {
        return Map.of("data", grants.network(identity.authenticate(auth).id(), activityId, body.publicKey()));
    }

    public record NetworkBody(@NotBlank String publicKey) {
    }
}
