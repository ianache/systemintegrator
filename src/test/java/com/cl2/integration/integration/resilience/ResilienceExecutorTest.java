package com.cl2.integration.integration.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilienceExecutorTest {
    private ResilienceExecutor executor;

    @BeforeEach
    void setup() {
        executor = new ResilienceExecutor();
    }

    @Test
    void shouldExecuteSuccessfullyWhenServiceHealthy() {
        UUID tenantId = UUID.randomUUID();
        String result = executor.execute(tenantId, "sap", () -> "SUCCESS");
        assertThat(result).isEqualTo("SUCCESS");
        assertThat(executor.getCircuitBreakerState(tenantId, "sap")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void shouldOpenCircuitOnRepeatedFailures() {
        UUID tenantId = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            try {
                executor.execute(tenantId, "sigo", () -> {
                    throw new RuntimeException("External API Down");
                });
            } catch (Exception ignored) {}
        }

        assertThat(executor.getCircuitBreakerState(tenantId, "sigo")).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> executor.execute(tenantId, "sigo", () -> "WOULD_FAIL"))
                .isInstanceOf(CircuitBreakerOpenException.class)
                .hasMessageContaining("Circuit breaker is OPEN");
    }
}
