package app.weavora.zhijie.controlplane.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class VoiceSignalingUrlTest {
    private final String localSfu = "http://127.0.0.1:7880";

    @Test void allowsExplicitProxyIncludingLoopbackDevelopment() {
        for (String entry : new String[]{"wss://voice.example.test", "ws://127.0.0.1:17881", "ws://[::1]:17881/"}) {
            assertThat(GrantService.requireSignalingUrl(entry, localSfu)).isEqualTo(entry);
        }
    }

    @Test void rejectsMissingUnsafeOrDirectSignalingAddresses() {
        for (String entry : new String[]{"", "http://voice.example.test", "ws://voice.example.test", "wss://voice.example.test/a",
                "wss://name@voice.example.test", "wss://voice.example.test/?token=value", "wss://voice.example.test/#fragment",
                "ws://127.0.0.1:7880", "ws://localhost:7880", "ws://[::1]:7880"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> GrantService.requireSignalingUrl(entry, localSfu));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> GrantService.requireSignalingUrl("wss://VOICE.example.test", "https://voice.example.test:443"));
    }
}
