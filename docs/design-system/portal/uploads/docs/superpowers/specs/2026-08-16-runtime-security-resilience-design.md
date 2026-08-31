# Design Spec: Runtime Security (Vault Secrets) & Distributed Resilience (Redis Rate Limiting & Circuit Breakers)

- **Date:** 2026-08-16
- **Status:** Approved
- **Scope:** Runtime Secret Resolution (Vault KV v2 & In-Memory Fallback) and Distributed Resilience (Redis Token Bucket Rate Limiting & Resilience4j Circuit Breaker)

---

## 1. Context & Objective

As specified in the PRD (v2.0), external systems (e.g. SAP, SIGO) require credentials that must never be stored in plain text or directly in application databases. Furthermore, external APIs enforce rate limits and require fault tolerance to prevent cascading outages when external services experience latency or downtime.

This module delivers:
1. **`SecretResolver`**: Secure resolution of `credentialRef` against HashiCorp Vault KV v2 with TTL caching and an In-Memory fallback for test and local environments.
2. **`DistributedRateLimiter`**: Tenant- and connector-scoped distributed rate limiting backed by Redis 7.4 using atomic Lua scripts.
3. **`ResilienceExecutor`**: Dynamic Circuit Breaker and Retry policies using Resilience4j to protect outbound external calls.

---

## 2. Component Architecture & Data Flow

```
                      +----------------------------------+
                      |     IntegrationProfile / API     |
                      +----------------------------------+
                                       |
                +----------------------+----------------------+
                |                                             |
                v                                             v
   +--------------------------+                  +--------------------------+
   |      SecretResolver      |                  |   DistributedRateLimiter |
   +--------------------------+                  +--------------------------+
        |                |                                    |
        v                v                                    v
+---------------+ +---------------+                   +---------------+
|  Vault Client | | InMemoryCache |                   |   Redis 7.4   |
| (KV v2 Engine)| |  (Local/Dev)  |                   | (Lua Scripts) |
+---------------+ +---------------+                   +---------------+
                                       |
                                       v
                         +--------------------------+
                         |    ResilienceExecutor    |
                         |   (Resilience4j CB/RT)   |
                         +--------------------------+
                                       |
                                       v
                         +--------------------------+
                         | External API (SAP / SIGO)|
                         +--------------------------+
```

### 2.1 Core Types & Data Models

#### Secret Resolution Models
```java
public enum AuthType {
    BEARER,
    BASIC,
    API_KEY,
    OAUTH2_CLIENT_CREDENTIALS,
    CUSTOM
}

public record ResolvedSecret(
    String credentialRef,
    AuthType authType,
    String username,
    String password,
    String apiKey,
    String token,
    Map<String, String> headers
) {}

public interface SecretResolver {
    ResolvedSecret resolve(String credentialRef, UUID tenantId);
    void putSecret(String credentialRef, UUID tenantId, ResolvedSecret secret); // for local/test injection
}
```

#### Rate Limiting & Resilience Models
```java
public record RateLimitResult(
    boolean allowed,
    long remainingTokens,
    long resetAfterSeconds
) {}

public interface DistributedRateLimiter {
    RateLimitResult tryAcquire(UUID tenantId, String connector, int requestsPerUnit, String unit);
}
```

---

## 3. Distributed Redis Rate Limiting Specification

### 3.1 Redis Key Structure
Key format: `ratelimit:{tenantId}:{connector}:{unit}`

### 3.2 Atomic Lua Script (Token Bucket / Sliding Window)
```lua
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local windowSeconds = tonumber(ARGV[2])

local current = redis.call('INCR', key)
if current == 1 then
    redis.call('EXPIRE', key, windowSeconds)
end

local ttl = redis.call('TTL', key)
if current <= limit then
    return {1, limit - current, ttl}
else
    return {0, 0, ttl}
end
```

---

## 4. Resilience4j Circuit Breaker Specification

1. **Registry Scope**: Registry instances keyed by `tenantId + ":" + connector`.
2. **Circuit Breaker Parameters**:
   * Sliding window size: 10 calls.
   * Failure rate threshold: 50%.
   * Wait duration in OPEN state: 10,000 ms.
   * Permitted calls in HALF_OPEN: 3 calls.
3. **Execution Contract**:
   * Outbound calls wrapped with `resilienceExecutor.execute(tenantId, connector, supplier)`.
   * Throws `CircuitBreakerOpenException` (HTTP 503 / Retryable error for Outbox Relay) when circuit is OPEN.

---

## 5. Configuration (`application.yml`)

```yaml
integration:
  security:
    vault:
      enabled: false
      uri: ${VAULT_ADDR:http://localhost:8200}
      token: ${VAULT_TOKEN:root}
      cache-ttl-seconds: 600
  resilience:
    rate-limiter:
      enabled: true
      default-requests-per-second: 50
    circuit-breaker:
      failure-rate-threshold: 50
      wait-duration-open-ms: 10000
```

---

## 6. Testing & Verification Strategy

1. **SecretResolverTest**:
   * Resolve secret with URI `vault:secret/data/tenants/{tenantId}/sap`.
   * Test in-memory fallback and cache TTL expiration.
   * Throws `SecretNotFoundException` on missing reference.
2. **DistributedRateLimiterTest**:
   * Test token acquisition up to configured limit.
   * Test rejection (429) on (N+1) request with proper reset TTL.
   * Verify tenant isolation (Tenant A quota exhausted does not affect Tenant B).
3. **ResilienceExecutorTest**:
   * Test successful execution path.
   * Verify state transition to OPEN on repeated failures.
   * Verify immediate fast-failure when OPEN.
4. **Full Reactor Test Suite**:
   * `mvn clean test` must pass 100% with zero failures.
