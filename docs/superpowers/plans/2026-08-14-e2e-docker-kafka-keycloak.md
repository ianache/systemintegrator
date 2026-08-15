# E2E Docker Kafka Keycloak Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the tenant-scoped integration-profile API, a Docker Compose environment with MySQL, Redis, Apache Kafka, application and Spring Cloud Gateway, plus deterministic and QA-backed E2E coverage.

**Architecture:** The Spring Boot application owns the domain, REST API, MySQL persistence and Kafka event producer. Spring Cloud Gateway is the public entry point, validates JWTs from Keycloak QA and propagates `tenant_id` as `X-Tenant-ID`. Docker Compose supplies MySQL, Redis, Kafka, the application and Gateway; Keycloak remains external.

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Cloud Gateway, Spring Security OAuth2 Resource Server, Spring Data JPA, MySQL 8.x, Flyway, Redis 7.x, Apache Kafka, Maven, JUnit 5, Spring Boot Test, Testcontainers, WireMock where an external HTTP stub is needed, Docker Compose.

## Global Constraints

- Keycloak issuer: `https://oauth2.qa.comsatel.com.pe/realms/microservicios`.
- Keycloak credentials must be supplied through environment variables and never committed or logged.
- Apache Kafka is the required messaging middleware; successful writes publish to `integration-profile.events` after the MySQL transaction commits.
- Every tenant-aware operation requires a valid UUID tenant; the tenant is never read from the request body.
- Gateway-propagated `X-Tenant-ID` is not trusted as a substitute for JWT validation when the request enters through the Gateway.
- Direct application access still validates `X-Tenant-ID` so bypassing the Gateway cannot create an unscoped operation.
- MySQL is the source of persisted profile state; Redis is an available infrastructure dependency, not an excuse to add unrequested business caching.
- No local Keycloak, Vault, Kafka Connect, SAP/SIGO connector, production DLQ or full Outbox implementation is part of this slice.
- Production code is added only after a failing automated test or an explicitly verified configuration contract exists.

## File Map

- `pom.xml`: Spring Boot, Cloud Gateway, JPA, Flyway, Redis, Kafka and test dependencies.
- `compose.yaml`: MySQL, Redis, Kafka, app and Gateway services, networks, volumes, healthchecks and dependencies.
- `.env.example`: non-secret local defaults and names of required Keycloak test variables.
- `.gitignore`: Maven output, Docker volumes, local environment files and IDE metadata.
- `src/main/java/com/cl2/integration/...`: domain, application, REST, persistence, tenant, Kafka and infrastructure code.
- `src/main/resources/application.yml`: app defaults and environment-backed datasource, Redis and Kafka settings.
- `gateway/`: standalone Spring Cloud Gateway module/configuration and tests.
- `src/test/java/com/cl2/integration/...`: unit, web, persistence, Kafka and app-level tests.
- `e2e/`: black-box test runner, token helper, API client, Kafka observer and E2E scenarios.
- `README.md`: setup, Keycloak variables, Compose commands, test profiles and troubleshooting.

### Task 1: Bootstrap the Maven application and test harness

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/cl2/integration/IntegrationApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/com/cl2/integration/IntegrationApplicationTest.java`
- Create: `.gitignore`

- [ ] **Step 1: Write the context-loading test.**

```java
@SpringBootTest
class IntegrationApplicationTest {
    @Test
    void applicationContextLoadsWithoutExternalQaServices() {}
}
```

- [ ] **Step 2: Run `mvn -Dtest=IntegrationApplicationTest test` and verify the expected missing-project failure.**
- [ ] **Step 3: Add the minimal Spring Boot project and application entry point.** Configure Java 21, Maven compiler, JUnit 5 and profiles so the default context does not call Keycloak QA.
- [ ] **Step 4: Run the focused test and verify it passes.**
- [ ] **Step 5: Commit `build: bootstrap integration application`.**

### Task 2: Implement tenant context, domain model and REST contract

**Files:**
- Create: `src/main/java/com/cl2/integration/infrastructure/tenant/TenantContext.java`
- Create: `src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java`
- Create: `src/main/java/com/cl2/integration/domain/model/IntegrationProfile.java`
- Create: `src/main/java/com/cl2/integration/domain/model/SourceOfTruth.java`
- Create: `src/main/java/com/cl2/integration/domain/model/SyncDirection.java`
- Create: `src/main/java/com/cl2/integration/domain/port/IntegrationProfileRepository.java`
- Create: `src/main/java/com/cl2/integration/application/IntegrationProfileService.java`
- Create: `src/main/java/com/cl2/integration/application/command/*.java`
- Test: `src/test/java/com/cl2/integration/infrastructure/tenant/*Test.java`
- Test: `src/test/java/com/cl2/integration/domain/model/IntegrationProfileTest.java`
- Test: `src/test/java/com/cl2/integration/application/IntegrationProfileServiceTest.java`

- [ ] **Step 1: Write failing tests for missing tenant, invalid UUID, domain invariants, tenant-scoped repository calls and logical deactivation.**
- [ ] **Step 2: Run the focused tests and confirm failures are caused by missing production types/behavior.**
- [ ] **Step 3: Implement `TenantContext` with non-inheritable `ThreadLocal<UUID>` and unconditional filter cleanup.**
- [ ] **Step 4: Implement `IntegrationProfile`, enums, commands, view and repository port.** Ensure `create`, `update(expectedVersion)` and `deactivate` enforce the approved invariants.
- [ ] **Step 5: Implement the service with tenant ID on every repository operation and transactional write boundaries.**
- [ ] **Step 6: Run all Task 2 tests and verify green.**
- [ ] **Step 7: Commit `feat: add tenant scoped profile use cases`.**

### Task 3: Add MySQL/Flyway persistence and Compose infrastructure

**Files:**
- Create: `src/main/resources/db/migration/V1__create_integration_profile.sql`
- Create: `src/main/java/com/cl2/integration/adapter/out/persistence/*.java`
- Create: `compose.yaml`
- Create: `.env.example`
- Create: `src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapterTest.java`

- [ ] **Step 1: Write the Testcontainers migration and tenant-isolation tests.** Assert required columns, active uniqueness, inactive history and cross-tenant non-visibility.
- [ ] **Step 2: Run the persistence test and verify it fails before the schema/adapter exists.**
- [ ] **Step 3: Add Flyway migration with UUID representation, UTC timestamps, optimistic version, tenant indexes and an active uniqueness constraint that permits inactive duplicates.**
- [ ] **Step 4: Implement separate JPA entity, Spring Data repository and domain persistence adapter.** Translate duplicate and optimistic-lock conflicts to stable application exceptions.
- [ ] **Step 5: Add Compose MySQL and Redis services with named volumes, non-secret defaults, healthchecks and an internal network.**
- [ ] **Step 6: Run focused persistence tests and `docker compose config`; verify exit code 0 and no unresolved required variables.**
- [ ] **Step 7: Commit `feat: add mysql redis and profile persistence`.**

### Task 4: Expose the API and publish Kafka events after commit

**Files:**
- Create: `src/main/java/com/cl2/integration/adapter/in/web/*.java`
- Create: `src/main/java/com/cl2/integration/messaging/IntegrationProfileEvent.java`
- Create: `src/main/java/com/cl2/integration/messaging/IntegrationProfileEventPublisher.java`
- Create: `src/main/java/com/cl2/integration/infrastructure/kafka/KafkaConfiguration.java`
- Modify: `src/main/java/com/cl2/integration/application/IntegrationProfileService.java`
- Test: `src/test/java/com/cl2/integration/adapter/in/web/IntegrationProfileControllerTest.java`
- Test: `src/test/java/com/cl2/integration/messaging/IntegrationProfileEventPublisherTest.java`
- Test: `src/test/java/com/cl2/integration/application/IntegrationProfileEventTransactionTest.java`

- [ ] **Step 1: Write failing MockMvc tests for CRUD, validation, tenant failures, `404`, `409`, `ProblemDetail` and logical delete.**
- [ ] **Step 2: Write a failing event test proving successful create/update/deactivate emits the correct event and a failed transaction emits none.**
- [ ] **Step 3: Run both focused test groups and verify expected failures.**
- [ ] **Step 4: Implement DTOs, controller, exception handler and tenant resolution.**
- [ ] **Step 5: Implement the Kafka event contract and publisher using an after-commit transaction synchronization or an equivalent Spring-supported post-commit mechanism.** Include `eventId`, type, profile ID, tenant ID, UTC timestamp and current state.
- [ ] **Step 6: Add Kafka producer configuration and Compose Kafka service with a single broker suitable for local E2E.** Configure advertised listeners so both Compose services and host-side tests can connect as documented.
- [ ] **Step 7: Run web/event tests and verify green.**
- [ ] **Step 8: Commit `feat: publish profile events to kafka`.**

### Task 5: Build the Spring Cloud Gateway middleware

**Files:**
- Create: `gateway/pom.xml`
- Create: `gateway/src/main/java/com/cl2/integration/gateway/GatewayApplication.java`
- Create: `gateway/src/main/java/com/cl2/integration/gateway/TenantClaimGatewayFilter.java`
- Create: `gateway/src/main/resources/application.yml`
- Create: `gateway/src/test/java/com/cl2/integration/gateway/TenantClaimGatewayFilterTest.java`
- Create: `gateway/src/test/java/com/cl2/integration/gateway/GatewaySecurityTest.java`
- Modify: `compose.yaml`

- [ ] **Step 1: Write failing security tests for valid JWT, invalid JWT, missing JWT and JWT without `tenant_id`.** Use a deterministic test issuer/JWK server for the default test profile.
- [ ] **Step 2: Write a failing filter test showing `tenant_id` becomes `X-Tenant-ID` and any client-supplied tenant header is overwritten.**
- [ ] **Step 3: Run Gateway tests and verify expected failures.**
- [ ] **Step 4: Implement OAuth2 Resource Server JWT validation using environment-backed `KEYCLOAK_ISSUER_URI`, defaulting to the QA issuer only in the QA profile.**
- [ ] **Step 5: Implement the tenant claim filter and route `/api/**` to the application service.** Strip/overwrite unsafe tenant headers and preserve correlation IDs.
- [ ] **Step 6: Add Gateway Dockerfile/configuration and expose the public port in Compose.** Configure app routing by service name, not localhost.
- [ ] **Step 7: Run Gateway tests and `docker compose config`; verify green.**
- [ ] **Step 8: Commit `feat: add keycloak secured gateway middleware`.**

### Task 6: Add deterministic Kafka/API E2E tests

**Files:**
- Create: `e2e/pom.xml` or the selected Maven test module configuration
- Create: `e2e/src/test/java/com/cl2/integration/e2e/ApiClient.java`
- Create: `e2e/src/test/java/com/cl2/integration/e2e/KafkaEventObserver.java`
- Create: `e2e/src/test/java/com/cl2/integration/e2e/IntegrationProfileE2ETest.java`
- Create: `src/test/resources/application-e2e.yml` if tests run in the main module
- Modify: `compose.yaml`

- [ ] **Step 1: Write the E2E scenarios for two tenants, CRUD, cross-tenant `404`, logical delete and Kafka event observation.**
- [ ] **Step 2: Run the E2E test against an empty environment and verify it fails for unavailable services before Compose is started.**
- [ ] **Step 3: Implement HTTP client helpers and a Kafka observer that filters by profile ID and tenant ID with a bounded timeout.**
- [ ] **Step 4: Add Compose app/Gateway images and healthchecks; make the E2E runner wait for Gateway health before issuing requests.**
- [ ] **Step 5: Start with `docker compose up -d --build mysql redis kafka app middleware`, run the E2E test, and verify all scenarios pass.**
- [ ] **Step 6: Stop with `docker compose down` and rerun from clean volumes to verify Flyway and Kafka initialization are reproducible.**
- [ ] **Step 7: Commit `test: cover end to end tenant kafka flow`.**

### Task 7: Add optional Keycloak QA E2E profile and documentation

**Files:**
- Create: `e2e/src/test/java/com/cl2/integration/e2e/KeycloakTokenClient.java`
- Create: `e2e/src/test/java/com/cl2/integration/e2e/KeycloakE2ETest.java`
- Modify: `.env.example`
- Modify: `README.md`

- [ ] **Step 1: Write the token-client contract test against a local WireMock issuer/token endpoint.**
- [ ] **Step 2: Implement password-grant/token acquisition only behind an explicit `qa-e2e` profile and environment variables `KEYCLOAK_TOKEN_URI`, `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_USERNAME`, `KEYCLOAK_PASSWORD` and tenant IDs.**
- [ ] **Step 3: Add the QA E2E scenario that obtains a token, calls the Gateway and verifies tenant propagation plus a Kafka event.**
- [ ] **Step 4: Ensure the default test command never contacts QA Keycloak; document the explicit command and required variables.**
- [ ] **Step 5: Document setup, commands, ports, health endpoints, topic name, cleanup and troubleshooting.**
- [ ] **Step 6: Commit `docs: document docker and keycloak e2e workflows`.**

## Final Verification

- [ ] Run `mvn test` for unit, web, persistence and application tests.
- [ ] Run `docker compose config` and inspect that no secret values are rendered from tracked files.
- [ ] Run a clean Compose cycle with `docker compose up -d --build`, healthchecks, E2E, and `docker compose down -v`.
- [ ] Run the optional QA profile only when the supplied Keycloak environment variables are present and the endpoint is reachable.
- [ ] Inspect `git diff --check`, `git status --short`, and the final commit list before reporting results.
