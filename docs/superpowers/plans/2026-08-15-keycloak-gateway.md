# Keycloak Gateway Middleware Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans (preferred). Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build and test a Spring Cloud Gateway middleware that validates Keycloak JWTs, derives the authenticated tenant, routes `/api/**` to the application, and runs in Docker Compose.

**Architecture:** Keep the existing Spring Boot application unchanged as the domain/API service. Add an independent reactive Gateway Maven module with Spring Security Resource Server and a global tenant filter. Compose will build the existing app and the new Gateway, while MySQL, Redis, and Kafka remain shared infrastructure.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring Cloud 2024.0.2, Spring Cloud Gateway, Spring Security OAuth2 Resource Server, WebFlux, JUnit 5, Reactor Test, Docker Compose.

## Global Constraints

- Keycloak issuer is `https://oauth2.qa.comsatel.com.pe/realms/microservicios` only when explicitly configured through `KEYCLOAK_ISSUER_URI` or the `qa-e2e` profile.
- No Keycloak credentials or tokens may be committed or logged.
- The Gateway must remove and replace client-supplied `X-Tenant-ID` with the UUID from the authenticated JWT `tenant_id` claim.
- Missing/invalid authentication returns `401`; authenticated requests without a valid `tenant_id` return `403`.
- Default Gateway tests must not contact external Keycloak.
- Existing application, tenant filter, persistence, and Testcontainers tests must remain passing.

---

### Task 1: Create the Gateway module and dependency baseline

**Files:**
- Create: `gateway/pom.xml`
- Create: `gateway/src/main/java/com/cl2/integration/gateway/GatewayApplication.java`
- Create: `gateway/src/main/resources/application.yml`
- Create: `gateway/src/test/java/com/cl2/integration/gateway/GatewayApplicationTest.java`

**Interfaces:**
- Produces a runnable `com.cl2.integration.gateway.GatewayApplication` on port `8081`.
- Exposes `APP_URI`, `GATEWAY_PORT`, and `KEYCLOAK_ISSUER_URI` as configuration properties.

- [ ] **Step 1: Write the failing context test**

```java
@SpringBootTest
class GatewayApplicationTest {
    @Test
    void contextLoads() { }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -f gateway/pom.xml test -Dtest=GatewayApplicationTest`

Expected: FAIL because the Gateway module and application class do not exist.

- [ ] **Step 3: Add the minimal Gateway Maven module**

Use Spring Cloud BOM `2024.0.2`, dependencies `spring-cloud-starter-gateway`, `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-actuator`, `spring-boot-starter-test`, `spring-security-test`, and `reactor-test`. Configure Java 21, Surefire, and Spring Boot packaging.

- [ ] **Step 4: Add the application and configuration**

```yaml
server:
  port: ${GATEWAY_PORT:8081}
spring:
  application:
    name: integration-gateway
  cloud:
    gateway:
      routes:
        - id: integration-api
          uri: ${APP_URI:http://localhost:8080}
          predicates:
            - Path=/api/**
management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -f gateway/pom.xml test -Dtest=GatewayApplicationTest`

Expected: PASS without contacting Keycloak.

- [ ] **Step 6: Commit**

```bash
git add gateway
git commit -m "feat: bootstrap keycloak gateway module"
```

### Task 2: Add JWT security and tenant propagation

**Files:**
- Create: `gateway/src/main/java/com/cl2/integration/gateway/security/GatewaySecurityConfig.java`
- Create: `gateway/src/main/java/com/cl2/integration/gateway/security/TenantClaimGatewayFilter.java`
- Create: `gateway/src/main/java/com/cl2/integration/gateway/security/InvalidTenantClaimException.java`
- Create: `gateway/src/test/java/com/cl2/integration/gateway/security/GatewaySecurityTest.java`
- Create: `gateway/src/test/java/com/cl2/integration/gateway/security/TenantClaimGatewayFilterTest.java`
- Modify: `gateway/src/main/resources/application.yml`

**Interfaces:**
- `TenantClaimGatewayFilter` implements `GlobalFilter` and `Ordered`.
- It reads `Authentication` from `ReactiveSecurityContextHolder`, obtains `Jwt.getClaimAsString("tenant_id")`, parses `UUID`, removes all incoming `X-Tenant-ID`, and adds the trusted UUID.
- `GatewaySecurityConfig` exposes a `SecurityWebFilterChain` with `oauth2ResourceServer(OAuth2ResourceServerSpec::jwt)`, permits only `/actuator/health`, and authenticates all other routes.

- [ ] **Step 1: Write failing security tests**

Cover these exact cases using `WebTestClient` and `SecurityMockServerConfigurers.mockJwt()`:

```java
@Test
void rejectsRequestsWithoutBearerToken() {
    webTestClient.get().uri("/api/v1/integration-profiles")
        .exchange().expectStatus().isUnauthorized();
}

@Test
void rejectsAuthenticatedJwtWithoutTenantClaim() {
    webTestClient.mutateWith(mockJwt()).get()
        .uri("/api/v1/integration-profiles")
        .exchange().expectStatus().isForbidden();
}
```

Add a filter test with a valid UUID claim and a malicious incoming header; assert the downstream exchange sees only the claim-derived header.

- [ ] **Step 2: Run focused tests to verify they fail**

Run: `mvn -f gateway/pom.xml test -Dtest=GatewaySecurityTest,TenantClaimGatewayFilterTest`

Expected: FAIL because no security chain or tenant filter exists.

- [ ] **Step 3: Implement minimal JWT security**

Configure JWT resource-server support through `spring.security.oauth2.resourceserver.jwt.issuer-uri`, but activate that property only when `KEYCLOAK_ISSUER_URI` is supplied. Use a test profile with a local mock decoder or `JwtIssuerReactiveAuthenticationManagerResolver` override so default tests never call QA.

- [ ] **Step 4: Implement tenant propagation**

For a valid authenticated JWT, remove all `X-Tenant-ID` values before forwarding. If the claim is absent or cannot be parsed as UUID, terminate with HTTP 403 and do not call the downstream route.

- [ ] **Step 5: Run focused tests to verify they pass**

Run: `mvn -f gateway/pom.xml test -Dtest=GatewaySecurityTest,TenantClaimGatewayFilterTest`

Expected: all security and propagation tests PASS.

- [ ] **Step 6: Commit**

```bash
git add gateway
git commit -m "feat: secure gateway with keycloak tenant propagation"
```

### Task 3: Add Compose application and middleware services

**Files:**
- Create: `Dockerfile`
- Create: `gateway/Dockerfile`
- Modify: `compose.yaml`
- Create: `.env.example`
- Modify: `README.md`

**Interfaces:**
- Compose service `app` listens on container port `8080` and depends on healthy `mysql`, `redis`, and `kafka`.
- Compose service `middleware` listens on container port `8081`, publishes `${GATEWAY_PORT:-8081}`, depends on healthy `app`, and routes to `http://app:8080`.
- `KEYCLOAK_ISSUER_URI` is an environment variable only; no user/password is stored in tracked files.

- [ ] **Step 1: Add build smoke tests/documented configuration**

Add a Compose validation check to the verification commands and document the expected service names, ports, and startup command in README.

- [ ] **Step 2: Run the validation to verify the services are missing**

Run: `docker compose config --quiet`

Expected before implementation: configuration remains valid but `app` and `middleware` are absent; the smoke check should identify the missing services.

- [ ] **Step 3: Add the application Dockerfile**

Use a Maven build stage with Eclipse Temurin 21 and a runtime stage with Eclipse Temurin JRE 21. Build the root application jar and run it with `java -jar`.

- [ ] **Step 4: Add the Gateway Dockerfile**

Use the same Java 21 build/runtime pattern, build `gateway/pom.xml`, and run the Gateway jar.

- [ ] **Step 5: Add Compose services and environment example**

Add healthchecks, internal network membership, `SPRING_DATASOURCE_*`, `SPRING_DATA_REDIS_*`, `KAFKA_BOOTSTRAP_SERVERS`, `APP_URI=http://app:8080`, and optional `KEYCLOAK_ISSUER_URI`. Do not add Keycloak credentials.

- [ ] **Step 6: Validate Compose**

Run: `docker compose config --quiet`

Expected: PASS; services `mysql`, `redis`, `kafka`, `app`, and `middleware` are rendered with no unresolved variables or committed secrets.

- [ ] **Step 7: Commit**

```bash
git add Dockerfile gateway/Dockerfile compose.yaml .env.example README.md
git commit -m "feat: add compose app and keycloak middleware"
```

### Task 4: Verify the complete local stack and hand off

**Files:**
- Modify: `README.md` if troubleshooting details are missing.

- [ ] **Step 1: Run Gateway tests**

Run: `mvn -f gateway/pom.xml test`

Expected: all Gateway tests PASS without external Keycloak.

- [ ] **Step 2: Run existing application tests**

Run: `mvn -q "-Dapi.version=1.40" "-Dspring.profiles.active=test" "-Dtest=IntegrationProfileEndToEndTest,IntegrationProfilePersistenceAdapterTest" test`

Expected: existing Testcontainers tests PASS.

- [ ] **Step 3: Validate and boot Compose**

Run: `docker compose config --quiet` then `docker compose up -d --build mysql redis kafka app middleware`.

Expected: all five services become healthy; if QA issuer is not supplied, the local Gateway remains available for deterministic tests but does not contact external Keycloak.

- [ ] **Step 4: Inspect state and clean up**

Run: `docker compose ps`, `docker compose logs --no-color middleware`, and `docker compose down`.

Expected: no startup errors and no application changes outside the feature worktree.

- [ ] **Step 5: Run final checks**

Run: `git diff --check` and `git status --short`.

Expected: no whitespace errors; only intentional feature files are changed.

- [ ] **Step 6: Commit documentation updates**

```bash
git add README.md
git commit -m "docs: document keycloak gateway workflow"
```
