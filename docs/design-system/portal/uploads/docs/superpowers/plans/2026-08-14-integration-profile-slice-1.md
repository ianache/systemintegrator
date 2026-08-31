# Integration Profile Slice 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a runnable Spring Boot application that manages tenant-isolated `IntegrationProfile` records through a REST API backed by MySQL and Flyway.

**Architecture:** Use one deployable application with hexagonal modules: domain, application, inbound web adapter, outbound JPA adapter, and infrastructure. Tenant identity enters through `X-Tenant-ID`, is stored in a request-scoped context, and is passed explicitly through application ports into tenant-filtered persistence queries.

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Web, Spring Data JPA/Hibernate, MySQL 8.x, Flyway, Maven, JUnit 5, Spring Boot Test, and Testcontainers for MySQL.

## Global Constraints

- Lenguaje: Java 21.
- Framework: Spring Boot 3.x.
- Persistence: Spring Data JPA / Hibernate.
- Database: MySQL 8.x.
- Migration: Flyway.
- Build: Maven.
- Testing: JUnit 5.
- Toda operación debe estar asociada explícitamente a un `tenant_id`.
- Está prohibido ejecutar operaciones de dominio tenant-aware sin tenant activo.
- `customer-service`, `vehicle-service` y `sales-order-service` no deben conocer sistemas, protocolos ni credenciales externas.
- No introducir tecnologías alternativas sin ADR aprobado.
- Las entidades JPA no se exponen directamente por la API.
- El dominio no depende de Spring, JPA ni HTTP.

---

## File Map

The project starts empty except for the PRD and design document. The implementation will create these focused units:

- `pom.xml`: Maven build, dependency versions, plugins, and test profiles.
- `src/main/java/com/cl2/integration/IntegrationApplication.java`: Spring Boot entry point.
- `src/main/java/com/cl2/integration/domain/model/IntegrationProfile.java`: domain aggregate and invariants.
- `src/main/java/com/cl2/integration/domain/model/SourceOfTruth.java`: domain enum.
- `src/main/java/com/cl2/integration/domain/model/SyncDirection.java`: domain enum.
- `src/main/java/com/cl2/integration/domain/port/IntegrationProfileRepository.java`: outbound domain port.
- `src/main/java/com/cl2/integration/application/IntegrationProfileService.java`: application use cases.
- `src/main/java/com/cl2/integration/application/command/CreateIntegrationProfileCommand.java`: create input.
- `src/main/java/com/cl2/integration/application/command/UpdateIntegrationProfileCommand.java`: update input.
- `src/main/java/com/cl2/integration/application/exception/*.java`: typed application errors.
- `src/main/java/com/cl2/integration/infrastructure/tenant/TenantContext.java`: request tenant holder.
- `src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java`: HTTP tenant extraction and cleanup.
- `src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java`: REST endpoints.
- `src/main/java/com/cl2/integration/adapter/in/web/dto/*.java`: request and response DTOs.
- `src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java`: `ProblemDetail` mapping.
- `src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfileJpaEntity.java`: JPA mapping.
- `src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataIntegrationProfileRepository.java`: Spring Data interface.
- `src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java`: outbound adapter and tenant predicates.
- `src/main/resources/db/migration/V1__create_integration_profile.sql`: initial schema.
- `src/main/resources/application.yml`: local defaults and Flyway/JPA configuration.
- `src/test/java/...`: unit, web, and integration tests matching production packages.
- `compose.yaml`: local MySQL service for manual API verification.
- `.gitignore`: Java/Maven/IDE/container generated files.

## Task 1: Bootstrap the Maven application

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/cl2/integration/IntegrationApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `.gitignore`
- Create: `compose.yaml`
- Test: `src/test/java/com/cl2/integration/IntegrationApplicationTest.java`

**Interfaces:**
- Produces the runnable Spring Boot application used by every later task.
- Exposes a Maven test command: `mvn test`.

- [ ] **Step 1: Write the application context test**

```java
@SpringBootTest
class IntegrationApplicationTest {
    @Test
    void applicationContextLoads() {
    }
}
```

- [ ] **Step 2: Add the minimal Maven project**

Configure Java 21, Spring Boot 3.x, Spring Web, validation, and test dependencies. Reserve Spring Data JPA, Flyway, and MySQL runtime dependencies for Task 4, when the persistence adapter and migration are introduced. Configure the Maven compiler plugin for Java 21 and Surefire for JUnit 5.

- [ ] **Step 3: Add the application entry point and local configuration**

Create `IntegrationApplication` with `@SpringBootApplication`. Add the application port and local server configuration, while leaving datasource, Flyway, and Hibernate settings for Task 4 so the bootstrap context test has no undeclared database dependency.

- [ ] **Step 4: Add local infrastructure files**

Create `compose.yaml` with MySQL 8.x, database `integration`, user `integration`, and a health check. Add ignores for `target/`, IDE metadata, local environment files, and Docker volumes.

- [ ] **Step 5: Run the bootstrap test**

Run: `mvn test -Dtest=IntegrationApplicationTest`

Expected: PASS with the Spring context loading without a database dependency.

- [ ] **Step 6: Commit the bootstrap**

```bash
git add pom.xml src .gitignore compose.yaml
git commit -m "build: bootstrap integration platform"
```

## Task 2: Implement the tenant context and HTTP boundary

**Files:**
- Create: `src/main/java/com/cl2/integration/infrastructure/tenant/TenantContext.java`
- Create: `src/main/java/com/cl2/integration/infrastructure/tenant/TenantFilter.java`
- Create: `src/main/java/com/cl2/integration/application/exception/TenantRequiredException.java`
- Create: `src/test/java/com/cl2/integration/infrastructure/tenant/TenantContextTest.java`
- Create: `src/test/java/com/cl2/integration/infrastructure/tenant/TenantFilterTest.java`

**Interfaces:**
- `TenantContext.set(UUID tenantId)`, `TenantContext.requireTenantId()`, and `TenantContext.clear()`.
- `TenantFilter` consumes `X-Tenant-ID` and produces a populated context for the request lifetime.

- [ ] **Step 1: Write context tests**

```java
@Test
void requireTenantIdFailsWhenNoTenantIsActive() {
    assertThatThrownBy(TenantContext::requireTenantId)
        .isInstanceOf(TenantRequiredException.class);
}

@Test
void contextCanSetReadAndClearTenant() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.set(tenantId);
    assertThat(TenantContext.requireTenantId()).isEqualTo(tenantId);
    TenantContext.clear();
    assertThatThrownBy(TenantContext::requireTenantId)
        .isInstanceOf(TenantRequiredException.class);
}
```

- [ ] **Step 2: Run the context tests and verify failure**

Run: `mvn test -Dtest=TenantContextTest`

Expected: FAIL because the context and exception do not yet exist.

- [ ] **Step 3: Implement a non-inheritable ThreadLocal context**

Use a static `ThreadLocal<UUID>` with `set`, `requireTenantId`, and `clear`. `requireTenantId` must throw `TenantRequiredException` when empty. Do not provide a nullable getter to application code.

- [ ] **Step 4: Write filter tests**

Cover valid UUID, missing header, malformed UUID, and cleanup after the filter chain. Missing or malformed headers must result in HTTP `400` at the web boundary.

- [ ] **Step 5: Implement `TenantFilter`**

Extend `OncePerRequestFilter`. Read exactly `X-Tenant-ID`, parse a UUID, set the context before `chain.doFilter`, and clear it in `finally`. Register it before controllers execute.

- [ ] **Step 6: Run tenant tests**

Run: `mvn test -Dtest=TenantContextTest,TenantFilterTest`

Expected: PASS.

- [ ] **Step 7: Commit tenant boundary**

```bash
git add src/main/java/com/cl2/integration/infrastructure/tenant src/main/java/com/cl2/integration/application/exception src/test/java/com/cl2/integration/infrastructure/tenant
git commit -m "feat: enforce tenant request context"
```

## Task 3: Implement the domain model and repository port

**Files:**
- Create: `src/main/java/com/cl2/integration/domain/model/IntegrationProfile.java`
- Create: `src/main/java/com/cl2/integration/domain/model/SourceOfTruth.java`
- Create: `src/main/java/com/cl2/integration/domain/model/SyncDirection.java`
- Create: `src/main/java/com/cl2/integration/domain/port/IntegrationProfileRepository.java`
- Create: `src/main/java/com/cl2/integration/application/exception/IntegrationProfileConflictException.java`
- Create: `src/main/java/com/cl2/integration/application/exception/IntegrationProfileNotFoundException.java`
- Test: `src/test/java/com/cl2/integration/domain/model/IntegrationProfileTest.java`

**Interfaces:**
- `IntegrationProfile.create(UUID id, UUID tenantId, String businessDomain, String externalSource, SyncDirection direction, SourceOfTruth sourceOfTruth)`.
- `IntegrationProfile.update(String businessDomain, String externalSource, SyncDirection direction, SourceOfTruth sourceOfTruth, long expectedVersion)`.
- `IntegrationProfile.deactivate()`.
- Repository methods: `save`, `findById(UUID tenantId, UUID id)`, `findAll(UUID tenantId, boolean activeOnly)`, `existsActive(UUID tenantId, String businessDomain, String externalSource)`, and `delete` is intentionally absent because deletion is logical.

- [ ] **Step 1: Write domain tests for valid creation and invariants**

Test that creation sets `active=true`, rejects null tenant, blank business domain, blank external source, and null enum values. Test that `deactivate` changes the state once and that update increments the version only when the expected version matches.

- [ ] **Step 2: Run domain tests and verify failure**

Run: `mvn test -Dtest=IntegrationProfileTest`

Expected: FAIL because the domain types do not exist.

- [ ] **Step 3: Implement enums and immutable domain state**

Use `UUID` for IDs, `String` for domain/source values, the two enums, `boolean active`, UTC `Instant` timestamps, and `long version`. Keep constructors/factory methods explicit and enforce invariants at the domain boundary.

- [ ] **Step 4: Implement the repository port**

Define the repository interface in the domain package. Include tenant ID in every lookup and uniqueness method signature so an adapter cannot accidentally perform an unscoped operation.

- [ ] **Step 5: Run domain tests**

Run: `mvn test -Dtest=IntegrationProfileTest`

Expected: PASS.

- [ ] **Step 6: Commit the domain**

```bash
git add src/main/java/com/cl2/integration/domain src/main/java/com/cl2/integration/application/exception src/test/java/com/cl2/integration/domain
git commit -m "feat: add integration profile domain"
```

## Task 4: Add the MySQL schema and persistence adapter

**Files:**
- Create: `src/main/resources/db/migration/V1__create_integration_profile.sql`
- Create: `src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfileJpaEntity.java`
- Create: `src/main/java/com/cl2/integration/adapter/out/persistence/SpringDataIntegrationProfileRepository.java`
- Create: `src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapter.java`
- Create: `src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapterTest.java`

**Interfaces:**
- Implements `IntegrationProfileRepository` from Task 3.
- Database schema exposes `tenant_id`, `active`, and the profile identity fields required by the uniqueness rule.

- [ ] **Step 1: Write the migration contract test**

Add a test that starts the application with MySQL Testcontainers and asserts that Flyway reports one successful migration and that `integration_profile` contains `tenant_id`, `active`, `version`, `created_at`, and `updated_at`.

- [ ] **Step 2: Create the Flyway migration**

Create `integration_profile` with UUIDs stored as `BINARY(16)` or a consistently configured UUID representation, enum values stored as strings, UTC timestamp columns, optimistic version, and indexes on `(tenant_id, active)` and `(tenant_id, business_domain, external_source, active)`. Enforce uniqueness of active profiles using a MySQL-compatible generated key or an equivalent schema constraint that permits multiple inactive historical rows.

- [ ] **Step 3: Map the domain to JPA**

Keep `IntegrationProfileJpaEntity` separate from the domain object. Map enum names as strings, configure optimistic locking with `@Version`, and convert between `Instant` and MySQL timestamps using the application timezone set to UTC.

- [ ] **Step 4: Implement tenant-scoped Spring Data queries**

Define repository queries requiring `tenantId` for `findById`, list, and active-existence checks. The adapter must map `Optional` absence to the application not-found exception and translate duplicate-key errors to `IntegrationProfileConflictException`.

- [ ] **Step 5: Run the persistence tests**

Run: `mvn test -Dtest=IntegrationProfilePersistenceAdapterTest`

Expected: PASS against MySQL 8.x, including migration, save/read, tenant isolation, logical deactivation, and active uniqueness.

- [ ] **Step 6: Commit persistence**

```bash
git add src/main/resources/db/migration src/main/java/com/cl2/integration/adapter/out/persistence src/test/java/com/cl2/integration/adapter/out/persistence
git commit -m "feat: persist tenant-scoped integration profiles"
```

## Task 5: Implement application use cases

**Files:**
- Create: `src/main/java/com/cl2/integration/application/IntegrationProfileService.java`
- Create: `src/main/java/com/cl2/integration/application/command/CreateIntegrationProfileCommand.java`
- Create: `src/main/java/com/cl2/integration/application/command/UpdateIntegrationProfileCommand.java`
- Create: `src/main/java/com/cl2/integration/application/IntegrationProfileView.java`
- Test: `src/test/java/com/cl2/integration/application/IntegrationProfileServiceTest.java`

**Interfaces:**
- `create(UUID tenantId, CreateIntegrationProfileCommand command): IntegrationProfileView`.
- `list(UUID tenantId, boolean activeOnly): List<IntegrationProfileView>`.
- `get(UUID tenantId, UUID profileId): IntegrationProfileView`.
- `update(UUID tenantId, UUID profileId, UpdateIntegrationProfileCommand command): IntegrationProfileView`.
- `deactivate(UUID tenantId, UUID profileId): void`.

- [ ] **Step 1: Write service tests with a fake repository**

Cover create, list, get, update, deactivation, duplicate active profile, not-found profile, and tenant mismatch. Assert that every repository call receives the tenant ID supplied to the use case.

- [ ] **Step 2: Run service tests and verify failure**

Run: `mvn test -Dtest=IntegrationProfileServiceTest`

Expected: FAIL because the commands, view, and service do not exist.

- [ ] **Step 3: Implement commands and view**

Use Java records for input commands and output view. Validate structural constraints at the web boundary and business invariants in the domain/service.

- [ ] **Step 4: Implement transactional use cases**

Create the service as an application component. Mark writes transactional, perform active uniqueness checks before save, call repository methods with tenant ID, and map domain objects to `IntegrationProfileView`.

- [ ] **Step 5: Run service tests**

Run: `mvn test -Dtest=IntegrationProfileServiceTest`

Expected: PASS.

- [ ] **Step 6: Commit application use cases**

```bash
git add src/main/java/com/cl2/integration/application src/test/java/com/cl2/integration/application
git commit -m "feat: add integration profile use cases"
```

## Task 6: Expose and verify the REST API

**Files:**
- Create: `src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java`
- Create: `src/main/java/com/cl2/integration/adapter/in/web/dto/CreateIntegrationProfileRequest.java`
- Create: `src/main/java/com/cl2/integration/adapter/in/web/dto/UpdateIntegrationProfileRequest.java`
- Create: `src/main/java/com/cl2/integration/adapter/in/web/dto/IntegrationProfileResponse.java`
- Create: `src/main/java/com/cl2/integration/adapter/in/web/ApiExceptionHandler.java`
- Create: `src/test/java/com/cl2/integration/adapter/in/web/IntegrationProfileControllerTest.java`

**Interfaces:**
- REST base path: `/api/v1/integration-profiles`.
- Header: `X-Tenant-ID`.
- `POST` returns `201` with a response body.
- `GET` collection returns `200`.
- `GET` item returns `200` or `404`.
- `PUT` returns `200`, `404`, or `409`.
- `DELETE` returns `204` and sets `active=false`.

- [ ] **Step 1: Write MockMvc contract tests**

Cover valid create, missing tenant header, malformed tenant header, invalid request body, list, get, update, logical delete, not found, duplicate active conflict, and cross-tenant access returning `404`.

- [ ] **Step 2: Run web tests and verify failure**

Run: `mvn test -Dtest=IntegrationProfileControllerTest`

Expected: FAIL because the controller, DTOs, and exception handler do not exist.

- [ ] **Step 3: Implement request and response DTOs**

Use records with Bean Validation: nonblank `businessDomain` and `externalSource`, and non-null `syncDirection` and `sourceOfTruth`. Do not include `tenantId` in request DTOs.

- [ ] **Step 4: Implement the controller**

Resolve the tenant only through `TenantContext.requireTenantId()`. Delegate to `IntegrationProfileService`, map commands and views, support `activeOnly` on collection GET, and return `204` for logical deletion.

- [ ] **Step 5: Implement `ProblemDetail` error mapping**

Map validation and malformed header to `400`, missing profile to `404`, conflicts to `409`, and unexpected exceptions to `500`. Include a stable error code and request correlation identifier without exposing stack traces.

- [ ] **Step 6: Run web tests**

Run: `mvn test -Dtest=IntegrationProfileControllerTest`

Expected: PASS.

- [ ] **Step 7: Commit the REST adapter**

```bash
git add src/main/java/com/cl2/integration/adapter/in/web src/test/java/com/cl2/integration/adapter/in/web
git commit -m "feat: expose integration profile REST API"
```

## Task 7: Complete clean integration verification

**Files:**
- Modify: `src/test/resources/application-test.yml`
- Modify: `src/test/java/com/cl2/integration/IntegrationApplicationTest.java`
- Create: `src/test/java/com/cl2/integration/IntegrationProfileEndToEndTest.java`
- Modify: `README.md`

**Interfaces:**
- The full test suite runs with `mvn test`.
- The documented local flow runs MySQL through `docker compose up -d` and starts the app with Maven.

- [ ] **Step 1: Configure Testcontainers MySQL**

Use a MySQL 8.x container for integration tests, inject its JDBC URL and credentials into Spring, and disable reliance on a developer-local database. Keep unit and web tests fast by separating them from the container-backed test class where possible.

- [ ] **Step 2: Write the end-to-end isolation test**

Create two tenant UUIDs. Create one profile for each tenant through the HTTP layer, assert each tenant sees only its own profile, assert cross-tenant GET/PUT/DELETE returns `404`, and assert deleting a profile leaves its row present with `active=false`.

- [ ] **Step 3: Run the complete suite**

Run: `mvn test`

Expected: PASS with all unit, web, migration, persistence, and end-to-end tests.

- [ ] **Step 4: Document local verification**

Add README commands:

```text
docker compose up -d
mvn spring-boot:run
curl -H "X-Tenant-ID: <tenant-uuid>" http://localhost:8080/api/v1/integration-profiles
```

Document required environment variables, migration behavior, API examples, and the fact that tenant UUIDs are supplied by the future gateway/JWT flow in later slices.

- [ ] **Step 5: Commit the verified slice**

```bash
git add src/test README.md
git commit -m "test: verify integration profile slice"
```

## Self-review checklist

- [x] Scope is one independently testable subsystem: tenant-scoped IntegrationProfile management.
- [x] The plan covers the approved architecture, data model, REST contract, errors, tenant isolation, migrations, and test criteria.
- [x] No `TBD`, `TODO`, or unspecified implementation step remains.
- [x] Later tasks consume interfaces defined by earlier tasks.
- [x] No external connector, Kafka, Inbox, Outbox, Vault, or credential work is smuggled into Slice 1.
