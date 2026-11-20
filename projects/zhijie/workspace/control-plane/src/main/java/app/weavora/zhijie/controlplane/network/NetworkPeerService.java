package app.weavora.zhijie.controlplane.network;

import app.weavora.zhijie.controlplane.activity.ActivityRepository;
import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.GrantRepository;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 候选端点只用于指定公钥的加密握手，不授予访问任意地址的权限。 */
@Service
public class NetworkPeerService {
    private final NetworkAccessService access;
    private final NetworkPeerMapper peers;
    private final GrantRepository grants;
    private final ActivityRepository activities;
    private final Clock clock;

    public NetworkPeerService(NetworkAccessService access, NetworkPeerMapper peers, GrantRepository grants,
                               ActivityRepository activities, Clock clock) {
        this.access = access;
        this.peers = peers;
        this.grants = grants;
        this.activities = activities;
        this.clock = clock;
    }

    @Transactional
    public List<Peer> register(String userId, String activityId, String ticket, String endpoint) {
        activities.lockUser(userId);
        var current = actor(userId, activityId, ticket);
        String candidate = current.allowedPaths().contains("DIRECT") ? endpoint(endpoint) : null;
        requireKey(current.grant().publicKey());
        peers.register(activityId, userId, current.grant().id(), current.grant().memberGeneration(), candidate, clock.instant());
        return listCurrent(current);
    }

    public List<Peer> list(String userId, String activityId, String ticket) {
        return listCurrent(actor(userId, activityId, ticket));
    }

    private NetworkAccessService.Access actor(String userId, String activityId, String ticket) {
        var current = access.validate(ticket);
        if (!current.grant().userId().equals(userId) || !current.grant().activityId().equals(activityId)
                || current.grant().allowedPaths() == null) throw access.denied();
        return current;
    }

    private List<Peer> listCurrent(NetworkAccessService.Access caller) {
        List<Peer> result = new ArrayList<>();
        for (var item : peers.list(caller.grant().activityId())) {
            if (item.userId().equals(caller.grant().userId())) continue;
            var grant = grants.findById(item.grantId()).orElse(null);
            if (grant == null || !grant.userId().equals(item.userId()) || !grant.activityId().equals(item.activityId())
                    || !grant.memberGeneration().equals(item.memberGeneration())) continue;
            try {
                var current = access.validateGrant(grant);
                if (grant.allowedPaths() == null) continue;
                requireKey(grant.publicKey());
                boolean direct = caller.allowedPaths().contains("DIRECT") && current.allowedPaths().contains("DIRECT");
                var expires = grant.expiresAt().isBefore(current.lease().expiresAt()) ? grant.expiresAt() : current.lease().expiresAt();
                result.add(new Peer(grant.userId(), current.ipv4(), grant.publicKey(), direct ? item.endpoint() : null,
                        grant.memberGeneration(), grant.leaseId(), expires));
            } catch (ApiException exception) {
                // 失权端点不进入新快照；现有 Peer 由客户端按快照替换并受授权期限约束。
            }
        }
        if (result.size() > 11) throw access.denied();
        return List.copyOf(result);
    }

    private void requireKey(String value) {
        try {
            byte[] key = Base64.getDecoder().decode(value);
            if (key.length != 32 || java.util.Arrays.equals(key, new byte[32])) throw access.denied();
        } catch (IllegalArgumentException | NullPointerException exception) { throw access.denied(); }
    }

    private String endpoint(String value) {
        try {
            if (value == null || !value.matches("[0-9.]{7,15}:[0-9]{1,5}")) throw invalidEndpoint();
            String[] parts = value.split(":", -1);
            InetAddress ip = InetAddress.getByName(parts[0]);
            int port = Integer.parseInt(parts[1]);
            if (!(ip instanceof Inet4Address) || ip.isAnyLocalAddress() || ip.isMulticastAddress()
                    || ip.getHostAddress().equals("255.255.255.255") || port < 1 || port > 65535) throw invalidEndpoint();
            return ip.getHostAddress() + ":" + port;
        } catch (java.net.UnknownHostException | NumberFormatException exception) { throw invalidEndpoint(); }
    }

    private ApiException invalidEndpoint() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NETWORK_ENDPOINT_INVALID", "必须提供有效 IPv4 UDP 候选端点", false);
    }

    public record Peer(String userId, String virtualIpv4, String publicKey, String endpoint,
                       Long memberGeneration, String leaseId, java.time.Instant expiresAt) {
    }
}
