package com.example.api_gateway_service.ratelimit;

import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;

public class RedisFixedWindowRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisFixedWindowRateLimiter.class);

    private static final String LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """;

    private final StringRedisTemplate redis;
    private final int windowSeconds;
    private final int maxRequests;
    private final String keyPrefix;
    private final RedisScript<Long> script;

    public RedisFixedWindowRateLimiter(StringRedisTemplate redis, int windowSeconds, int maxRequests) {
        this(redis, windowSeconds, maxRequests, "rl");
    }

    public RedisFixedWindowRateLimiter(StringRedisTemplate redis, int windowSeconds, int maxRequests, String keyPrefix) {
        if (windowSeconds <= 0)
            throw new IllegalArgumentException("windowSeconds must be > 0");
        if (maxRequests <= 0)
            throw new IllegalArgumentException("maxRequests must be > 0");
        this.redis = redis;
        this.windowSeconds = windowSeconds;
        this.maxRequests = maxRequests;
        this.keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? "rl" : keyPrefix;

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(LUA);
        redisScript.setResultType(Long.class);
        this.script = redisScript;
    }

    public Result tryAcquire(String clientKey) {
        long nowEpochSeconds = Instant.now().getEpochSecond();
        long windowStart = nowEpochSeconds - (nowEpochSeconds % windowSeconds);
        long resetEpochSeconds = windowStart + windowSeconds;

        String redisKey = keyPrefix + ":" + clientKey + ":" + windowStart;

        Long current = redis.execute(script, List.of(redisKey), String.valueOf(windowSeconds));
        long count = current != null ? current : 0L;

        boolean allowed = count <= maxRequests;
        int remaining = (int) Math.max(0, maxRequests - count);
        long retryAfterSeconds = allowed ? 0 : Math.max(1, resetEpochSeconds - nowEpochSeconds);

        if (!allowed) {
            log.warn("Rate limit reached for key: {}. Count: {}, Max: {}", clientKey, count, maxRequests);
            Metrics.counter("ratelimiter.rejected", "key", clientKey).increment();
        } else {
            Metrics.counter("ratelimiter.allowed", "key", clientKey).increment();
        }

        return new Result(allowed, remaining, maxRequests, resetEpochSeconds, retryAfterSeconds);
    }

    public record Result(
            boolean allowed,
            int remaining,
            int limit,
            long resetEpochSeconds,
            long retryAfterSeconds) {
    }
}
