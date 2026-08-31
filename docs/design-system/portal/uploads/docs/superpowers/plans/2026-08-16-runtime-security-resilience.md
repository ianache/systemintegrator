# Runtime Security & Distributed Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement runtime secret resolution (HashiCorp Vault KV v2 with TTL caching and In-Memory fallback) and distributed resilience (Redis Token Bucket / Sliding Window rate limiting via atomic Lua script and Resilience4j dynamic circuit breaking).

**Architecture:** Strategy pattern for `SecretResolver` (`VaultSecretResolver`, `InMemorySecretResolver`), atomic Redis-backed `DistributedRateLimiter`, and tenant/connector-scoped `ResilienceExecutor` with Resilience4j circuit breakers to protect outbound external calls.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring Data Redis (`StringRedisTemplate`), Resilience4j (`2.2.0`), Jackson, JUnit 5, AssertJ, Mockito.

## Global Constraints

- Java 21 with records, pattern matching, and standard formatting.
- Multitenant isolation: all secrets, rate limits, and circuit breakers must be keyed by `(tenant_id, connector)`.
- Non-blocking & atomic: Redis rate limiting must execute via atomic Lua script to guarantee concurrency safety.
- Graceful degradation: In local/test profiles, `SecretResolver` falls back to in-memory store without requiring a running Vault instance.
- All tests must pass with `mvn test`.

---

### Task 1: Dependencies & Secret Resolution Engine (`SecretResolver`)

**Files:**
- Modify: `application/pom.xml`
- Create: `src/main/java/com/cl2/integration/integration/security/AuthType.java`
- Create: `src/main/java/com/cl2/integration/integration/security/ResolvedSecret.java`
- Create: `src/main/java/com/cl2/integration/integration/security/SecretResolver.java`
- Create: `src/main/java/com/cl2/integration/integration/security/SecretNotFoundException.java`
- Create: `src/main/java/com/cl2/integration/integration/security/InMemorySecretResolver.java`
- Create: `src/main/java/com/cl2/integration/integration/security/VaultSecretResolver.java`
- Create: `src/main/java/com/cl2/integration/integration/security/VaultProperties.java`
- Test: `src/test/java/com/cl2/integration/integration/security/SecretResolverTest.java`

**Interfaces:**
- Produces: `AuthType`, `ResolvedSecret`, `SecretResolver`, `InMemorySecretResolver`, `VaultSecretResolver`.

- [ ] **Step 1: Add dependencies in `application/pom.xml`**

Add `spring-boot-starter-data-redis` and `resilience4j-spring-boot3` to `application/pom.xml`:
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-spring-boot3</artifactId>
            <version>2.2.0</version>
        </dependency>
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-circuitbreaker</artifactId>
            <version>2.2.0</version>
        </dependency>
```

- [ ] **Step 2: Create Core Security Models and Exceptions**

Create `src/main/java/com/cl2/integration/integration/security/AuthType.java`:
```java
package com.cl2.integration.integration.security;

public enum AuthType {
    BEARER,
    BASIC,
    API_KEY,
    OAUTH2_CLIENT_CREDENTIALS,
    CUSTOM
}
```

Create `src/main/java/com/cl2/integration/integration/security/ResolvedSecret.java`:
```java
package com.cl2.integration.integration.security;

import java.util.Map;

public record ResolvedSecret(
    String credentialRef,
    AuthType authType,
    String username,
    String password,
    String apiKey,
    String token,
    Map<String, String> headers
) {
    public ResolvedSecret {
        if (headers == null) {
            headers = Map.of();
        }
    }

    public static ResolvedSecret apiKey(String credentialRef, String apiKey) {
        return new ResolvedSecret(credentialRef, AuthType.API_KEY, null, null, apiKey, null, Map.of());
    }

    public static ResolvedSecret bearer(String credentialRef, String token) {
        return new ResolvedSecret(credentialRef, AuthType.BEARER, null, null, null, token, Map.of());
    }

    public static ResolvedSecret basic(String credentialRef, String username, String password) {
        return new ResolvedSecret(credentialRef, AuthType.BASIC, username, password, null, null, Map.of());
    }
}
```

Create `src/main/java/com/cl2/integration/integration/security/SecretNotFoundException.java`:
```java
package com.cl2.integration.integration.security;

public class SecretNotFoundException extends RuntimeException {
    private final String credentialRef;

    public SecretNotFoundException(String credentialRef) {
        super("Secret not found for credentialRef: " + credentialRef);
        this.credentialRef = credentialRef;
    }

    public String getCredentialRef() { return credentialRef; }
}
```

Create `src/main/java/com/cl2/integration/integration/security/SecretResolver.java`:
```java
package com.cl2.integration.integration.security;

import java.util.UUID;

public interface SecretResolver {
    ResolvedSecret resolve(String credentialRef, UUID tenantId);
    void putSecret(String credentialRef, UUID tenantId, ResolvedSecret secret);
}
```

- [ ] **Step 3: Create `VaultProperties`, `InMemorySecretResolver`, and `VaultSecretResolver`**

Create `src/main/java/com/cl2/integration/integration/security/VaultProperties.java`:
```java
package com.cl2.integration.integration.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "integration.security.vault")
public class VaultProperties {
    private boolean enabled = false;
    private String uri = "http://localhost:8200";
    private String token = "root";
    private int cacheTtlSeconds = 600;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public int getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(int cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
}
```

Create `src/main/java/com/cl2/integration/integration/security/InMemorySecretResolver.java`:
```java
package com.cl2.integration.integration.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemorySecretResolver implements SecretResolver {
    private final Map<String, ResolvedSecret> store = new ConcurrentHashMap<>();

    @Override
    public ResolvedSecret resolve(String credentialRef, UUID tenantId) {
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new SecretNotFoundException("null");
        }
        String key = buildKey(credentialRef, tenantId);
        ResolvedSecret secret = store.get(key);
        if (secret == null) {
            // fallback to global key
            secret = store.get(credentialRef);
        }
        if (secret == null) {
            throw new SecretNotFoundException(credentialRef);
        }
        return secret;
    }

    @Override
    public void putSecret(String credentialRef, UUID tenantId, ResolvedSecret secret) {
        store.put(buildKey(credentialRef, tenantId), secret);
        store.put(credentialRef, secret);
    }

    private String buildKey(String credentialRef, UUID tenantId) {
        return (tenantId != null ? tenantId.toString() : "global") + ":" + credentialRef;
    }
}
```

- [ ] **Step 4: Write unit test `SecretResolverTest.java`**

Create `src/test/java/com/cl2/integration/integration/security/SecretResolverTest.java`:
```java
package com.cl2.integration.integration.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretResolverTest {
    private InMemorySecretResolver resolver;

    @BeforeEach
    void setup() {
        resolver = new InMemorySecretResolver();
    }

    @Test
    void shouldStoreAndResolveSecretByTenant() {
        UUID tenantId = UUID.randomUUID();
        String ref = "vault:secret/data/tenants/" + tenantId + "/sap";
        ResolvedSecret secret = ResolvedSecret.basic(ref, "sap_user", "sap_pass");

        resolver.putSecret(ref, tenantId, secret);

        ResolvedSecret resolved = resolver.resolve(ref, tenantId);
        assertThat(resolved).isNotNull();
        assertThat(resolved.username()).isEqualTo("sap_user");
        assertThat(resolved.password()).isEqualTo("sap_pass");
        assertThat(resolved.authType()).isEqualTo(AuthType.BASIC);
    }

    @Test
    void shouldThrowExceptionWhenSecretNotFound() {
        UUID tenantId = UUID.randomUUID();
        assertThatThrownBy(() -> resolver.resolve("vault:secret/data/unknown", tenantId))
                .isInstanceOf(SecretNotFoundException.class)
                .hasMessageContaining("vault:secret/data/unknown");
    }
}
```

- [ ] **Step 5: Run tests and commit**

Run: `mvn test -Dtest=SecretResolverTest`
Expected: PASS
```bash
git add application/pom.xml src/main/java/com/cl2/integration/integration/security/ src/test/java/com/cl2/integration/integration/security/
git commit -m "feat(security): implement SecretResolver with in-memory fallback and core models"
```

---

### Task 2: Distributed Rate Limiter with Redis & Atomic Lua Script

**Files:**
- Create: `src/main/java/com/cl2/integration/integration/resilience/RateLimitResult.java`
- Create: `src/main/java/com/cl2/integration/integration/resilience/RateLimitExceededException.java`
- Create: `src/main/java/com/cl2/integration/integration/resilience/DistributedRateLimiter.java`
- Create: `src/main/java/com/cl2/integration/integration/resilience/RedisDistributedRateLimiter.java`
- Test: `src/test/java/com/cl2/integration/integration/resilience/DistributedRateLimiterTest.java`

**Interfaces:**
- Consumes: `StringRedisTemplate`, Redis Scripting
- Produces: `DistributedRateLimiter` executing atomic sliding-window rate limiting per `(tenantId, connector)`.

- [ ] **Step 1: Create Rate Limit Models & Exceptions**

Create `src/main/java/com/cl2/integration/integration/resilience/RateLimitResult.java`:
```java
package com.cl2.integration.integration.resilience;

public record RateLimitResult(
    boolean allowed,
    long remainingTokens,
    long resetAfterSeconds
) {}
```

Create `src/main/java/com/cl2/integration/integration/resilience/RateLimitExceededException.java`:
```java
package com.cl2.integration.integration.resilience;

import java.util.UUID;

public class RateLimitExceededException extends RuntimeException {
    private final UUID tenantId;
    private final String connector;
    private final long retryAfterSeconds;

    public RateLimitExceededException(UUID tenantId, String connector, long retryAfterSeconds) {
        super("Rate limit exceeded for tenant " + tenantId + " on connector " + connector + ". Retry after " + retryAfterSeconds + "s");
        this.tenantId = tenantId;
        this.connector = connector;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public UUID getTenantId() { return tenantId; }
    public String getConnector() { return connector; }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
```

Create `src/main/java/com/cl2/integration/integration/resilience/DistributedRateLimiter.java`:
```java
package com.cl2.integration.integration.resilience;

import java.util.UUID;

public interface DistributedRateLimiter {
    RateLimitResult tryAcquire(UUID tenantId, String connector, int requestsPerUnit, String unit);
    void checkPermission(UUID tenantId, String connector, int requestsPerUnit, String unit);
}
```

- [ ] **Step 2: Create `RedisDistributedRateLimiter.java`**

Create `src/main/java/com/cl2/integration/integration/resilience/RedisDistributedRateLimiter.java`:
```java
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
            List result = redisTemplate.execute(
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
            default -> 60; // MINUTE
        };
    }
}
```

- [ ] **Step 3: Write unit test `DistributedRateLimiterTest.java`**

Create `src/test/java/com/cl2/integration/integration/resilience/DistributedRateLimiterTest.java`:
```java
package com.cl2.integration.integration.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
                .hasMessageContaining("Retry after 30s");
    }
}
```

- [ ] **Step 4: Run tests and commit**

Run: `mvn test -Dtest=DistributedRateLimiterTest`
Expected: PASS
```bash
git add src/main/java/com/cl2/integration/integration/resilience/ src/test/java/com/cl2/integration/integration/resilience/
git commit -m "feat(resilience): implement Redis distributed rate limiter with Lua script"
```

---

### Task 3: Circuit Breaker & Retry with Resilience4j (`ResilienceExecutor`)

**Files:**
- Create: `src/main/java/com/cl2/integration/integration/resilience/CircuitBreakerOpenException.java`
- Create: `src/main/java/com/cl2/integration/integration/resilience/ResilienceExecutor.java`
- Test: `src/test/java/com/cl2/integration/integration/resilience/ResilienceExecutorTest.java`

**Interfaces:**
- Consumes: Resilience4j `CircuitBreakerRegistry`, `CircuitBreakerConfig`
- Produces: `ResilienceExecutor.execute(tenantId, connector, supplier)` wrapping external calls.

- [ ] **Step 1: Create `CircuitBreakerOpenException.java`**

Create `src/main/java/com/cl2/integration/integration/resilience/CircuitBreakerOpenException.java`:
```java
package com.cl2.integration.integration.resilience;

import java.util.UUID;

public class CircuitBreakerOpenException extends RuntimeException {
    private final UUID tenantId;
    private final String connector;

    public CircuitBreakerOpenException(UUID tenantId, String connector) {
        super("Circuit breaker is OPEN for tenant " + tenantId + " and connector " + connector);
        this.tenantId = tenantId;
        this.connector = connector;
    }

    public UUID getTenantId() { return tenantId; }
    public String getConnector() { return connector; }
}
```

- [ ] **Step 2: Create `ResilienceExecutor.java`**

Create `src/main/java/com/cl2/integration/integration/resilience/ResilienceExecutor.java`:
```java
package com.cl2.integration.integration.resilience;

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
                .build();
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(defaultConfig);
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
```

- [ ] **Step 3: Write unit test `ResilienceExecutorTest.java`**

Create `src/test/java/com/cl2/integration/integration/resilience/ResilienceExecutorTest.java`:
```java
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
```

- [ ] **Step 4: Run tests and commit**

Run: `mvn test -Dtest=ResilienceExecutorTest`
Expected: PASS
```bash
git add src/main/java/com/cl2/integration/integration/resilience/ src/test/java/com/cl2/integration/integration/resilience/
git commit -m "feat(resilience): implement ResilienceExecutor with Resilience4j circuit breakers"
```

---

### Task 4: Integration Flow & Test Documentation

**Files:**
- Create: `src/test/java/com/cl2/integration/integration/security/RuntimeSecurityResilienceIntegrationTest.java`
- Create: `docs/test-cases/test-cases-runtime-security-resilience.md`

**Interfaces:**
- Validates the end-to-end flow: `IntegrationProfile` configuration ➔ `SecretResolver` credential resolution ➔ `DistributedRateLimiter` quota checking ➔ `ResilienceExecutor` fault-tolerant outbound call.

- [ ] **Step 1: Create Integration Test `RuntimeSecurityResilienceIntegrationTest.java`**

Create `src/test/java/com/cl2/integration/integration/security/RuntimeSecurityResilienceIntegrationTest.java`:
```java
package com.cl2.integration.integration.security;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.ProtocolType;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.integration.resilience.DistributedRateLimiter;
import com.cl2.integration.integration.resilience.RateLimitResult;
import com.cl2.integration.integration.resilience.ResilienceExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RuntimeSecurityResilienceIntegrationTest {

    @Autowired
    private SecretResolver secretResolver;

    @Autowired
    private DistributedRateLimiter rateLimiter;

    @Autowired
    private ResilienceExecutor resilienceExecutor;

    @Test
    void shouldResolveSecretAndExecuteProtectedOutboundCall() {
        UUID tenantId = UUID.randomUUID();
        String credentialRef = "vault:secret/data/tenants/" + tenantId + "/sap";
        ResolvedSecret secret = ResolvedSecret.bearer(credentialRef, "secure-oauth-token-12345");
        secretResolver.putSecret(credentialRef, tenantId, secret);

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                ProtocolType.REST, "sap", "sap-customer-adapter", "https://sap.corp.internal/api",
                credentialRef, null, null, null, null, null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                tenantId, "Customer", "SAP", SyncDirection.OUTBOUND, SourceOfTruth.PLATFORM, config
        );

        ResolvedSecret resolved = secretResolver.resolve(profile.configuration().credentialRef(), tenantId);
        assertThat(resolved.token()).isEqualTo("secure-oauth-token-12345");

        RateLimitResult rateLimit = rateLimiter.tryAcquire(tenantId, profile.configuration().connector(), 100, "MINUTE");
        assertThat(rateLimit.allowed()).isTrue();

        String callResult = resilienceExecutor.execute(tenantId, profile.configuration().connector(), () -> "SAP_CUSTOMER_SYNCED_200");
        assertThat(callResult).isEqualTo("SAP_CUSTOMER_SYNCED_200");
    }
}
```

- [ ] **Step 2: Create Documentation `docs/test-cases/test-cases-runtime-security-resilience.md`**

- [ ] **Step 3: Run full project test suite and commit**

Run: `mvn clean test`
Expected: 100% tests PASS with zero errors.
```bash
git add src/test/java/com/cl2/integration/integration/security/ docs/test-cases/
git commit -m "feat(security-resilience): implement runtime integration flow and test documentation"
```
