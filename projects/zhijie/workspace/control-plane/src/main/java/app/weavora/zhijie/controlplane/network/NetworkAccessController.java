package app.weavora.zhijie.controlplane.network;

import app.weavora.zhijie.controlplane.activity.ActivityCoordinator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** Helper 只持有短期网络票据，无需持有账号令牌或服务管理密钥。 */
@RestController
@RequestMapping("/api/v1/network-access")
public class NetworkAccessController {
    private final NetworkAccessService access;
    private final NetworkPeerService peers;

    public NetworkAccessController(NetworkAccessService access, NetworkPeerService peers) {
        this.access = access;
        this.peers = peers;
    }

    @PostMapping("/validate")
    public AccessResponse validate(@Valid @RequestBody AccessBody body) {
        var current = access.validate(body.ticket());
        var grant = current.grant();
        List<NetworkPeerService.Peer> currentPeers = peers.list(grant.userId(), grant.activityId(), body.ticket());
        Instant expiry = grant.expiresAt().isBefore(current.lease().expiresAt()) ? grant.expiresAt() : current.lease().expiresAt();
        return new AccessResponse(grant.activityId(), current.lease().activityGeneration(), grant.memberGeneration(),
                current.lease().deviceId(), grant.leaseId(), grant.policyRevision(), current.allowedPaths(), current.ipv4(),
                ActivityCoordinator.ACTIVITY_CIDR, grant.publicKey(), expiry, currentPeers);
    }

    public record AccessBody(@NotBlank @Size(max = 512) String ticket) { }
    public record AccessResponse(String activityId, Long activityGeneration, Long memberGeneration, String deviceId,
            String leaseId, Long policyRevision, List<String> allowedPaths, String virtualIpv4, String activityCidr,
            String publicKey, Instant expiresAt, List<NetworkPeerService.Peer> peers) { }
}
