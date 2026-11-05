package app.weavora.zhijie.controlplane.identity;

public interface EmailDispatchPort {
    boolean isAvailable();

    void sendChallenge(String email, ChallengePurpose purpose, String code);
}
