package app.weavora.zhijie.controlplane.network;

import app.weavora.zhijie.controlplane.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RelayGrantValidationService {
    private final NetworkAccessService access;

    public RelayGrantValidationService(NetworkAccessService access) {
        this.access = access;
    }

    public RelayValidationResponse validate(String ticket) {
        try {
            var current = access.validate(ticket);
            var grant = current.grant();
            boolean allowRelay = current.allowedPaths().contains("RELAY") && grant.allowedPaths() != null
                    && java.util.Arrays.asList(grant.allowedPaths().split(",")).contains("RELAY");
            var lease = current.lease();
            var expires = grant.expiresAt().isBefore(lease.expiresAt()) ? grant.expiresAt() : lease.expiresAt();
            return new RelayValidationResponse(1, ticket, grant.activityId(), lease.activityGeneration(), grant.memberGeneration(),
                    lease.id(), lease.deviceId(), grant.userId(), current.ipv4(), grant.policyRevision(), allowRelay,
                    expires.toEpochMilli());
        } catch (ApiException exception) {
            throw new ApiException(HttpStatus.FORBIDDEN, "RELAY_GRANT_INVALID", "中继票据无效或已撤销", false);
        }
    }
}
