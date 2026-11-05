package app.weavora.zhijie.controlplane.identity;

import app.weavora.zhijie.controlplane.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class UnavailableEmailDispatchAdapter implements EmailDispatchPort {
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public void sendChallenge(String email, ChallengePurpose purpose, String code) {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE", "邮件服务尚未接入", false);
    }
}
