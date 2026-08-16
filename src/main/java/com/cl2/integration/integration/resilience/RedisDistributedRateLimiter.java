package com.cl2.integration.integration.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class RedisDistributedRateLimiter implements DistributedRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RedisDistributedRateLimiter.class);

    private static final String LUA_SCRIPT = """
        local key = KEYS[1]
        local limit = tonumber(ARGV[1])
        local windowSeconds = tonumber(ARGV[2])

        local current = redis.call('INCR', key)
        if current == 1 then
            redis.call('EXPIRE', key, windowSeconds)
        end

        local ttl = redis.call('TTL', key)
        if ttl < 0 then
            ttl = windowSeconds
        end

        if current <= limit then
            return {1, limit - current, ttl}
        else
            return {0, 0, ttl}
        end
        """;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> script;

    public RedisDistributedRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>(LUA_SCRIPT, List.class);
    }

    @Override
    public RateLimitResult tryAcquire(UUID tenantId, String connector, int requestsPerUnit, String unit) {
        if (requestsPerUnit <= 0) {
            return new RateLimitResult(true, Long.MAX_VALUE, 0);
        }

        long windowSeconds = calculateWindowSeconds(unit);
        String key = "ratelimit:" + (tenantId != null ? tenantId.toString() : "global") + ":" + (connector != null ? connector : "default") + ":" + windowSeconds;

        try {
            List<?> result = redisTemplate.execute(
                    script,
                    List.of(key),
                    String.valueOf(requestsPerUnit),
                    String.valueOf(windowSeconds)
            );

            if (result != null && result.size() >= 3) {
                boolean allowed = ((Number) result.get(0)).intValue() == 1;
                long remaining = ((Number) result.get(1)).longValue();
                long resetAfter = ((Number) result.get(2)).longValue();
                return new RateLimitResult(allowed, remaining, Math.max(1, resetAfter));
            }
        } catch (Exception ex) {
            log.warn("Redis rate limiter unavailable: {}. Failing open to allow traffic.", ex.getMessage());
            return new RateLimitResult(true, requestsPerUnit, windowSeconds); // fail-open on redis error
        }

        return new RateLimitResult(true, requestsPerUnit, windowSeconds);
    }

    @Override
    public void checkPermission(UUID tenantId, String connector, int requestsPerUnit, String unit) {
        RateLimitResult result = tryAcquire(tenantId, connector, requestsPerUnit, unit);
        if (!result.allowed()) {
            throw new RateLimitExceededException(tenantId, connector, result.resetAfterSeconds());
        }
    }

    private long calculateWindowSeconds(String unit) {
        if (unit == null) {
            return 60;
        }
        return switch (unit.toUpperCase()) {
            case "SECOND", "SECONDS" -> 1;
            case "HOUR", "HOURS" -> 3600;
            case "DAY", "DAYS" -> 86400;
            default -> 60; // MINUTE / MINUTES / fallback
        };
    }
}
