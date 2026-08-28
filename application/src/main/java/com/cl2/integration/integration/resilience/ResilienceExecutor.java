package com.cl2.integration.integration.resilience;

import com.cl2.integration.adapter.out.http.HttpOutboundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class ResilienceExecutor {
    private static final Logger log = LoggerFactory.getLogger(ResilienceExecutor.class);

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ResilienceExecutor() {
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                // A 4xx response is a permanent business rejection (e.g. duplicate
                // key) that will never succeed on retry. Counting it as a circuit
                // failure lets a flood of duplicates (e.g. a DLQ replay) trip the
                // breaker and block brand-new, valid traffic to the same connector.
                .recordException(ResilienceExecutor::isTransientFailure)
                .build();
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(defaultConfig);
    }

    private static boolean isTransientFailure(Throwable throwable) {
        if (throwable instanceof HttpOutboundException httpOutboundException) {
            Integer statusCode = httpOutboundException.getStatusCode();
            return statusCode == null || statusCode >= 500;
        }
        return true;
    }

    public <T> T execute(UUID tenantId, String connector, Supplier<T> operation) {
        String instanceKey = (tenantId != null ? tenantId.toString() : "global") + ":" + (connector != null ? connector : "default");
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(instanceKey);

        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker, operation).get();
        } catch (CallNotPermittedException ex) {
            log.warn("Call blocked by Circuit Breaker for {}: {}", instanceKey, ex.getMessage());
            throw new CircuitBreakerOpenException(tenantId, connector);
        }
    }

    public CircuitBreaker.State getCircuitBreakerState(UUID tenantId, String connector) {
        String instanceKey = (tenantId != null ? tenantId.toString() : "global") + ":" + (connector != null ? connector : "default");
        return circuitBreakerRegistry.circuitBreaker(instanceKey).getState();
    }
}
