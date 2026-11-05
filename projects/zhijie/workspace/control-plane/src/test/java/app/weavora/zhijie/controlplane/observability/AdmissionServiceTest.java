package app.weavora.zhijie.controlplane.observability;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.weavora.zhijie.controlplane.common.ApiException;
import app.weavora.zhijie.controlplane.grant.GrantRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class AdmissionServiceTest {
    @Test
    void capacityRejectionIsSpecificToTheAffectedChannel() {
        GrantRepository grants = mock(GrantRepository.class);
        when(grants.countActive("VOICE")).thenReturn(2);
        when(grants.countActive("NETWORK")).thenReturn(0);
        AdmissionService service = new AdmissionService(grants, 2, 10,
                new ObservationService(new SimpleMeterRegistry()));

        service.admit("NETWORK");
        assertThatThrownBy(() -> service.admit("VOICE"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("VOICE_CAPACITY_LIMITED");
    }
}
