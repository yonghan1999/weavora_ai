package app.weavora.zhijie.controlplane.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {
    @Test
    void authenticatedRelayHasSeparateBoundedBudgetAndBadKeysCannotConsumeIt() throws Exception {
        var clock = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
        var filter = new RateLimitFilter(new ObjectMapper(), clock, "test-management-key", 100, 50);
        for (int index = 0; index < 61; index++) {
            var request = new MockHttpServletRequest("POST", "/internal/relay/v1/validate");
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-Relay-Key", "wrong-key");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, mock(FilterChain.class));
            assertThat(response.getStatus()).isEqualTo(index < 60 ? 200 : 429);
        }
        for (int index = 0; index < 101; index++) {
            var request = new MockHttpServletRequest("POST", "/internal/relay/v1/validate");
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-Relay-Key", "test-management-key");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, mock(FilterChain.class));
            assertThat(response.getStatus()).isEqualTo(index < 100 ? 200 : 429);
        }
        assertThatThrownBy(() -> new RateLimitFilter(new ObjectMapper(), clock, "key", 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitFilter(new ObjectMapper(), clock, "key", 1, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sensitiveEndpointUsesLimitedBucketAndReturnsRetryableError() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC), "", 2048, 1024);
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
