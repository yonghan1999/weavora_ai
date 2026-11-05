package app.weavora.zhijie.controlplane.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RateLimitFilter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getRequestURI();
        boolean sensitive = path.startsWith("/api/v1/auth/") || path.contains("grants") || path.startsWith("/ops/");
        int capacity = sensitive ? 10 : 60;
        double perSecond = sensitive ? 5.0 : 30.0;
        String key = request.getRemoteAddr() + ":" + request.getMethod() + ":" + path;
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(capacity, clock.millis()));
        if (!bucket.allow(capacity, perSecond, clock.millis())) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(Map.of("error", Map.of(
                    "code", "RATE_LIMITED", "message", "请求过于频繁，请稍后重试", "retryable", true
            ))));
            return;
        }
        if (buckets.size() > 10_000) buckets.clear();
        chain.doFilter(request, response);
    }

    static class Bucket {
        private double tokens;
        private long lastMillis;

        Bucket(double tokens, long lastMillis) {
            this.tokens = tokens;
            this.lastMillis = lastMillis;
        }

        synchronized boolean allow(double capacity, double perSecond, long now) {
            tokens = Math.min(capacity, tokens + Math.max(0, now - lastMillis) * perSecond / 1000.0);
            lastMillis = now;
            if (tokens < 1.0) return false;
            tokens -= 1.0;
            return true;
        }
    }
}
