package app.weavora.zhijie.controlplane.network;

import app.weavora.zhijie.controlplane.identity.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/activities/{activityId}/network-peers")
public class NetworkPeerController {
    private final NetworkPeerService peers;
    private final IdentityService identity;

    public NetworkPeerController(NetworkPeerService peers, IdentityService identity) {
        this.peers = peers;
        this.identity = identity;
    }

    @PostMapping("/register")
    public Map<String, List<NetworkPeerService.Peer>> register(@RequestHeader("Authorization") String auth,
            @PathVariable String activityId, @Valid @RequestBody RegisterBody body) {
        return Map.of("data", peers.register(identity.authenticate(auth).id(), activityId, body.ticket(), body.endpoint()));
    }

    @PostMapping("/query")
    public Map<String, List<NetworkPeerService.Peer>> query(@RequestHeader("Authorization") String auth,
            @PathVariable String activityId, @Valid @RequestBody QueryBody body) {
        return Map.of("data", peers.list(identity.authenticate(auth).id(), activityId, body.ticket()));
    }

    public record RegisterBody(@NotBlank @Size(max = 512) String ticket, @Size(max = 64) String endpoint) { }
    public record QueryBody(@NotBlank @Size(max = 512) String ticket) { }
}
