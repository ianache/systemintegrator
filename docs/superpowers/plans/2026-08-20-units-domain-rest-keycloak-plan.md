# Units Domain Ingestion with Keycloak-Secured Outbound REST Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable independent ingestion and synchronization of Units entities (`brands`, `models`, `vehicles`) via segregated Kafka event topics, transforming and dispatching outbound HTTP requests to Keycloak-secured REST APIs using OAuth2 Client Credentials (JWT Bearer).

**Architecture:** Extend `ResolvedSecret`, `VaultSecretResolver`, and `InMemorySecretResolver` to support `OAUTH2_CLIENT_CREDENTIALS`. Integrate `OAuth2TokenCacheManager` into `HttpOutboundClient` to dynamically fetch and cache Keycloak JWTs for outbound REST calls. Update `OutboundEventDispatcher` and test end-to-end multi-tenant domain events routing for `brands`, `models`, and `vehicles`.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring Kafka, Spring Data JPA, WireMock, Resilience4j, Redis (Lettuce), Jackson, JUnit 5, AssertJ.

## Global Constraints
- All paths must be under `application/src/main` and `application/src/test`.
- Maintain 100% test pass rate across the entire reactor (`mvn test`).
- Code style: DRY, YAGNI, proper package naming, robust exception handling.

---

### Task 1: Extend `ResolvedSecret` and Secret Resolvers for OAuth2 Client Credentials

**Files:**
- Modify: `application/src/main/java/com/cl2/integration/integration/security/ResolvedSecret.java`
- Modify: `application/src/main/java/com/cl2/integration/integration/security/VaultSecretResolver.java`
- Modify: `application/src/main/java/com/cl2/integration/integration/security/InMemorySecretResolver.java`
- Test: `application/src/test/java/com/cl2/integration/integration/security/SecretResolverTest.java`

**Interfaces:**
- Produces: `ResolvedSecret.oauth2(String credentialRef, String tokenUrl, String clientId, String clientSecret, String scope)`
- Produces: `ResolvedSecret.tokenUrl()`, `ResolvedSecret.clientId()`, `ResolvedSecret.clientSecret()`, `ResolvedSecret.scope()`

- [ ] **Step 1: Write failing test in SecretResolverTest**
Add tests verifying `ResolvedSecret.oauth2(...)` and `VaultSecretResolver` constructing OAuth2 secrets when Vault contains `tokenUrl`, `clientId`, `clientSecret`.

- [ ] **Step 2: Run test to verify it fails**
Run: `mvn test -pl application "-Dtest=SecretResolverTest"`
Expected: FAIL due to missing OAuth2 methods and fields on `ResolvedSecret`.

- [ ] **Step 3: Update `ResolvedSecret.java` and `VaultSecretResolver.java`**
Add `tokenUrl`, `clientId`, `clientSecret`, `scope` to `ResolvedSecret` and factory method `oauth2(...)`. In `VaultSecretResolver`, inspect Vault data for `tokenUrl` / `token_url` and `clientId` to construct `oauth2` secret if present.

- [ ] **Step 4: Run test to verify it passes**
Run: `mvn test -pl application "-Dtest=SecretResolverTest"`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add application/src/main/java/com/cl2/integration/integration/security/ResolvedSecret.java application/src/main/java/com/cl2/integration/integration/security/VaultSecretResolver.java application/src/test/java/com/cl2/integration/integration/security/SecretResolverTest.java
git commit -m "feat(security): support OAuth2 client credentials in ResolvedSecret and VaultSecretResolver"
```

---

### Task 2: Enhance `OAuth2TokenCacheManager` and Connect with Keycloak Token Endpoint

**Files:**
- Modify: `application/src/main/java/com/cl2/integration/adapter/out/generic/security/OAuth2TokenCacheManager.java`
- Test: `application/src/test/java/com/cl2/integration/adapter/out/generic/security/OAuth2TokenCacheManagerTest.java`

**Interfaces:**
- Produces: `OAuth2TokenCacheManager.getAccessToken(String tenantId, String tokenUrl, String clientId, String clientSecret, String scope)`
- Produces: Default HTTP `TokenFetcher` invoking Keycloak token endpoint with `grant_type=client_credentials`.

- [ ] **Step 1: Write failing test in OAuth2TokenCacheManagerTest**
Add test asserting token fetch via OpenID Connect form URL-encoded POST and caching behavior with custom parameters.

- [ ] **Step 2: Run test to verify it fails**
Run: `mvn test -pl application "-Dtest=OAuth2TokenCacheManagerTest"`
Expected: FAIL due to missing signature or method.

- [ ] **Step 3: Update `OAuth2TokenCacheManager.java`**
Implement overloaded `getAccessToken(String tenantId, String tokenUrl, String clientId, String clientSecret, String scope)` and provide default RestClient-based `TokenFetcher`.

- [ ] **Step 4: Run test to verify it passes**
Run: `mvn test -pl application "-Dtest=OAuth2TokenCacheManagerTest"`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add application/src/main/java/com/cl2/integration/adapter/out/generic/security/OAuth2TokenCacheManager.java application/src/test/java/com/cl2/integration/adapter/out/generic/security/OAuth2TokenCacheManagerTest.java
git commit -m "feat(security): enhance OAuth2TokenCacheManager with keycloak client credentials support"
```

---

### Task 3: Integrate OAuth2 Token Injection into `HttpOutboundClient` & `OutboundEventDispatcher`

**Files:**
- Modify: `application/src/main/java/com/cl2/integration/adapter/out/http/HttpOutboundClient.java`
- Modify: `application/src/main/java/com/cl2/integration/integration/outbound/OutboundEventDispatcher.java`
- Test: `application/src/test/java/com/cl2/integration/adapter/out/http/HttpOutboundClientTest.java`
- Test: `application/src/test/java/com/cl2/integration/integration/outbound/OutboundEventDispatcherTest.java`

**Interfaces:**
- Consumes: `OAuth2TokenCacheManager.getAccessToken(...)`
- Produces: `HttpOutboundClient.send(String endpoint, ResolvedSecret secret, String payload, UUID tenantId)` or attaches Bearer JWT when `AuthType.OAUTH2_CLIENT_CREDENTIALS`.

- [ ] **Step 1: Write failing test in HttpOutboundClientTest & OutboundEventDispatcherTest**
Test that when `ResolvedSecret.oauth2(...)` is provided, `HttpOutboundClient` requests an access token and adds `Authorization: Bearer <jwt>` header.

- [ ] **Step 2: Run test to verify it fails**
Run: `mvn test -pl application "-Dtest=HttpOutboundClientTest,OutboundEventDispatcherTest"`
Expected: FAIL due to missing OAuth2 Bearer injection.

- [ ] **Step 3: Update `HttpOutboundClient.java` & `OutboundEventDispatcher.java`**
In `HttpOutboundClient`, inject `OAuth2TokenCacheManager`. In `applyAuthHeaders`, if `secret.authType() == AuthType.OAUTH2_CLIENT_CREDENTIALS`, obtain token and set `Authorization: Bearer <token>`.

- [ ] **Step 4: Run test to verify it passes**
Run: `mvn test -pl application "-Dtest=HttpOutboundClientTest,OutboundEventDispatcherTest"`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add application/src/main/java/com/cl2/integration/adapter/out/http/HttpOutboundClient.java application/src/main/java/com/cl2/integration/integration/outbound/OutboundEventDispatcher.java application/src/test/java/com/cl2/integration/adapter/out/http/HttpOutboundClientTest.java application/src/test/java/com/cl2/integration/integration/outbound/OutboundEventDispatcherTest.java
git commit -m "feat(outbound): inject OAuth2 JWT Bearer tokens in HttpOutboundClient"
```

---

### Task 4: End-to-End Testing for Units Ingestion (`brands`, `models`, `vehicles`) with WireMock

**Files:**
- Create: `application/src/test/java/com/cl2/integration/integration/units/UnitsDomainOutboundE2ETest.java`
- Modify: `application/src/test/resources/application-test.yml` (if needed)

**Interfaces:**
- Simulates Keycloak `/realms/cl2/protocol/openid-connect/token` returning JWT.
- Simulates CL2 Core REST endpoints (`/api/v1/brands`, `/api/v1/models`, `/api/v1/vehicles`).
- Verifies Inbound Extraction -> Outbox -> Kafka -> Inbox -> Keycloak Token Fetch -> Outbound REST POST with Bearer Token.

- [ ] **Step 1: Write `UnitsDomainOutboundE2ETest.java`**
Configure WireMock for Keycloak token issuance and endpoints for brands, models, and vehicles. Emit events to Kafka topics `integration.brands.events`, `integration.models.events`, `integration.vehicles.events` and assert WireMock receives matching POST requests with `Authorization: Bearer mock-jwt-token`.

- [ ] **Step 2: Run test to verify it compiles and executes**
Run: `mvn test -pl application "-Dtest=UnitsDomainOutboundE2ETest"`
Expected: PASS

- [ ] **Step 3: Run full reactor test suite**
Run: `mvn test`
Expected: 100% BUILD SUCCESS across all modules.

- [ ] **Step 4: Commit**
```bash
git add application/src/test/java/com/cl2/integration/integration/units/UnitsDomainOutboundE2ETest.java
git commit -m "test(units): add e2e integration test for brands, models, and vehicles outbound REST flow"
```
