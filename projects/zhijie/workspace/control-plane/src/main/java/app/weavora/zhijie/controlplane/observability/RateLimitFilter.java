package app.weavora.zhijie.controlplane.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String relayKey;
    private final int relayCapacity;
    private final double relayPerSecond;

    public RateLimitFilter(ObjectMapper objectMapper, Clock clock,
                           @Value("${zhijie.relay.management-key:}") String relayKey,
                           @Value("${zhijie.relay.validation-burst:2048}") int relayCapacity,
                           @Value("${zhijie.relay.validation-per-second:1024}") double relayPerSecond) {
        if (relayCapacity < 1 || relayCapacity > 65536 || !Double.isFinite(relayPerSecond)
                || relayPerSecond <= 0 || relayPerSecond > 65536) throw new IllegalArgumentException("Invalid relay validation rate budget");
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.relayKey = relayKey;
        this.relayCapacity = relayCapacity;
        this.relayPerSecond = relayPerSecond;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getRequestURI();
        // 同一来源签发不同房间的邀请码共用预算；桶键不含房间标识或邀请码。
        String budgetPath = request.getMethod().equals("POST") && path.startsWith("/api/v1/rooms/") && path.endsWith("/invitations")
                ? "/api/v1/rooms/{room}/invitations" : path;
        boolean roomInvitation = request.getMethod().equals("POST") && (budgetPath.equals("/api/v1/rooms/{room}/invitations")
                || budgetPath.equals("/api/v1/invitations/redeem-room"));
        boolean sensitive = roomInvitation || path.startsWith("/api/v1/auth/") || path.contains("grants") || path.startsWith("/ops/");
        String supplied = request.getHeader("X-Relay-Key");
        boolean internalRelay = path.equals("/internal/relay/v1/validate") && request.getMethod().equals("POST")
                && !relayKey.isEmpty() && supplied != null
                && MessageDigest.isEqual(relayKey.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
        int capacity = internalRelay ? relayCapacity : sensitive ? 10 : 60;
        double perSecond = internalRelay ? relayPerSecond : sensitive ? 5.0 : 30.0;
        String key = request.getRemoteAddr() + ":" + request.getMethod() + ":" + budgetPath + (internalRelay ? ":authenticated-relay" : "");
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
