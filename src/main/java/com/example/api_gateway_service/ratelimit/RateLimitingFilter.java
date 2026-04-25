package com.example.api_gateway_service.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private static final String[] LIMITED_PREFIXES = {
            "/auth/",
            "/properties/",
            "/bookmarks/",
            "/api/",
            "/chatbot/"
    };

    private final RateLimitProperties properties;
    private final StringRedisTemplate redisTemplate;
    private volatile RedisFixedWindowRateLimiter redisLimiter;

    public RateLimitingFilter(RateLimitProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled())
            return true;

        String path = request.getRequestURI();
        if (path == null)
            return true;

        if (path.startsWith("/actuator") || path.startsWith("/error") || path.equals("/favicon.ico"))
            return true;

        for (String prefix : LIMITED_PREFIXES) {
            if (path.startsWith(prefix))
                return false;
        }

        return true;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String key = clientKey(request);

        RedisFixedWindowRateLimiter.Result result;
        String backend = "redis";
        try {
            RedisFixedWindowRateLimiter limiter = getOrCreateRedisLimiter();
            result = limiter.tryAcquire(key);
        } catch (Exception e) {
            backend = "redis-error";
            log.error("Redis rate limiter failed code=503 error={}", e.toString());
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"rate_limiter_unavailable\"}");
            return;
        }

        response.setHeader("X-RateLimit-Backend", backend);
        response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(result.resetEpochSeconds()));

        if (!result.allowed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"rate_limited\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private RedisFixedWindowRateLimiter getOrCreateRedisLimiter() {
        RedisFixedWindowRateLimiter existing = redisLimiter;
        if (existing != null)
            return existing;
        synchronized (this) {
            if (redisLimiter == null) {
                redisLimiter = new RedisFixedWindowRateLimiter(
                        redisTemplate,
                        properties.getWindowSeconds(),
                        properties.getMaxRequests());
            }
            return redisLimiter;
        }
    }

    private static String clientKey(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isBlank())
                return first;
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank())
            return realIp.trim();

        String remote = request.getRemoteAddr();
        return remote != null ? remote : "unknown";
    }
}
