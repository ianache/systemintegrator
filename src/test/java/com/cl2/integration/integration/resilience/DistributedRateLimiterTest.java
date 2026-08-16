package com.cl2.integration.integration.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DistributedRateLimiterTest {
    private StringRedisTemplate redisTemplate;
    private RedisDistributedRateLimiter rateLimiter;

    @BeforeEach
    void setup() {
        redisTemplate = mock(StringRedisTemplate.class);
        rateLimiter = new RedisDistributedRateLimiter(redisTemplate);
    }

    @Test
    void shouldAllowWhenUnderLimit() {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.execute(any(RedisScript.class), any(), eq("10"), eq("60")))
                .thenReturn(List.of(1L, 9L, 55L));

        RateLimitResult result = rateLimiter.tryAcquire(tenantId, "sap", 10, "MINUTE");

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(9);
        assertThat(result.resetAfterSeconds()).isEqualTo(55);
    }

    @Test
    void shouldRejectWhenLimitExceeded() {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.execute(any(RedisScript.class), any(), eq("5"), eq("60")))
                .thenReturn(List.of(0L, 0L, 30L));

        RateLimitResult result = rateLimiter.tryAcquire(tenantId, "sigo", 5, "MINUTE");

        assertThat(result.allowed()).isFalse();
        assertThat(result.remainingTokens()).isEqualTo(0);

        assertThatThrownBy(() -> rateLimiter.checkPermission(tenantId, "sigo", 5, "MINUTE"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Retry after 30s")
                .satisfies(ex -> {
                    RateLimitExceededException rle = (RateLimitExceededException) ex;
                    assertThat(rle.getTenantId()).isEqualTo(tenantId);
                    assertThat(rle.getConnector()).isEqualTo("sigo");
                    assertThat(rle.getRetryAfterSeconds()).isEqualTo(30);
                });
    }

    @Test
    void shouldAllowWhenRequestsPerUnitIsZeroOrNegative() {
        RateLimitResult result = rateLimiter.tryAcquire(UUID.randomUUID(), "sap", 0, "MINUTE");
        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void shouldFailOpenWhenRedisThrowsException() {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.execute(any(RedisScript.class), any(), eq("10"), eq("60")))
                .thenThrow(new RuntimeException("Redis connection refused"));

        RateLimitResult result = rateLimiter.tryAcquire(tenantId, "sap", 10, "MINUTE");

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(10);
        assertThat(result.resetAfterSeconds()).isEqualTo(60);
    }

    @ParameterizedTest
    @CsvSource({
            "SECOND, 1",
            "SECONDS, 1",
            "MINUTE, 60",
            "MINUTES, 60",
            "HOUR, 3600",
            "HOURS, 3600",
            "DAY, 86400",
            "DAYS, 86400",
            "UNKNOWN, 60"
    })
    void shouldCalculateCorrectWindowSeconds(String unit, String expectedWindow) {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.execute(any(RedisScript.class), any(), eq("100"), eq(expectedWindow)))
                .thenReturn(List.of(1L, 99L, Long.parseLong(expectedWindow)));

        RateLimitResult result = rateLimiter.tryAcquire(tenantId, "sap", 100, unit);

        assertThat(result.allowed()).isTrue();
    }
}
