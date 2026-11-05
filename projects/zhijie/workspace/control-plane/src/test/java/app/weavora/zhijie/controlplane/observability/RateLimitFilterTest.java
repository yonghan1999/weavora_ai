package app.weavora.zhijie.controlplane.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {
    @Test
    void sensitiveEndpointUsesLimitedBucketAndReturnsRetryableError() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new ObjectMapper(), Clock.systemUTC());
        MockHttpServletResponse limited = null;
        for (int index = 0; index < 11; index++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("127.0.0.1");
            limited = new MockHttpServletResponse();
            filter.doFilter(request, limited, mock(FilterChain.class));
        }
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getContentAsString()).contains("RATE_LIMITED", "retryable");
    }
}
