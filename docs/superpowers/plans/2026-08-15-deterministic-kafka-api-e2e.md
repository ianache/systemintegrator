# Deterministic Kafka/API E2E Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reproducible MySQL/Kafka E2E suite for tenant-isolated integration profiles and complete the missing profile event publication contract.

**Architecture:** The application will publish integration-profile events after a successful transaction through an application event and an `AFTER_COMMIT` listener backed by the existing Kafka producer contract. A separate `e2e/` Maven module will depend on the application artifact, start the application with Testcontainers MySQL/Kafka and random HTTP port, and verify API responses plus Kafka events using a bounded, filtered observer.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Maven, JUnit 5, Spring Boot Test, Testcontainers MySQL/Kafka, Spring Kafka, Jackson, Apache Kafka.

## Global Constraints

- The E2E suite must not contact Keycloak QA or any external issuer.
- Tenant identity for this suite is supplied through `X-Tenant-ID`; the Gateway + Keycloak flow remains Task 7.
- Testcontainers must use MySQL 8.4 and an Apache Kafka image compatible with the existing local Docker setup.
- Kafka assertions must filter by `eventType`, `profileId`, and `tenantId`, with a finite timeout and no fixed sleeps.
- Existing unit, MVC, persistence, Gateway and Testcontainers tests must remain passing.
- Do not commit credentials, access tokens or environment-specific secrets.

---

### Task 1: Publish integration-profile events after commit

**Files:**
- Create: `src/main/java/com/cl2/integration/integration/profile/IntegrationProfileEvent.java`
- Create: `src/main/java/com/cl2/integration/integration/profile/IntegrationProfileEventPublisher.java`
- Create: `src/main/java/com/cl2/integration/integration/profile/IntegrationProfileEventListener.java`
- Modify: `src/main/java/com/cl2/integration/application/IntegrationProfileService.java`
- Test: `src/test/java/com/cl2/integration/application/IntegrationProfileEventTransactionTest.java`
- Test: `src/test/java/com/cl2/integration/integration/profile/IntegrationProfileEventPublisherTest.java`

**Interfaces:**
- `IntegrationProfileEvent` is an immutable record carrying `eventId`, `eventType`, `profileId`, `tenantId`, `occurredAt`, and the current profile state.
- `IntegrationProfileEventPublisher.publish(IntegrationProfileEvent event)` sends JSON to `integration-profile.events` using the profile ID as Kafka key.
- `IntegrationProfileEventListener` receives an application event and invokes the publisher from `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`.

- [ ] **Step 1: Write failing publisher tests.** Verify serialized JSON contains the event ID, exact event type, profile ID, tenant ID, UTC timestamp and state fields, and that the Kafka key equals `profileId`.
- [ ] **Step 2: Run the focused tests and verify they fail because the event contract/publisher does not exist.**
- [ ] **Step 3: Write the failing transaction test.** Mock the repository and event publisher, call create/update/deactivate, and assert an event is published only after a successful transaction boundary; assert a repository exception produces no event.
- [ ] **Step 4: Implement the event record, publisher and `AFTER_COMMIT` listener.** Use `ApplicationEventPublisher` from the service so persistence remains inside the service transaction and Kafka publication happens after commit.
- [ ] **Step 5: Update `IntegrationProfileService` to emit `IntegrationProfileCreated`, `IntegrationProfileUpdated` and `IntegrationProfileDeactivated` with the resulting state.
- [ ] **Step 6: Run publisher, transaction, service and controller tests; refactor only after all are green.**
- [ ] **Step 7: Commit `feat: publish integration profile events after commit`.**

### Task 2: Create the E2E Maven module and container bootstrap

**Files:**
- Modify: `pom.xml`
- Create: `e2e/pom.xml`
- Create: `e2e/src/test/java/com/cl2/integration/e2e/E2eApplicationTest.java`
- Create: `e2e/src/test/resources/application-e2e.yml`

**Interfaces:**
- Root Maven project exposes the `e2e` module while retaining the existing application artifact.
- `E2eApplicationTest` starts `MySQLContainer<?>` and `KafkaContainer`, registers dynamic datasource and Kafka properties, activates `e2e`, and starts the application on a random port.
- The module test command is `mvn -pl e2e -am test`.

- [ ] **Step 1: Add the module POM and a failing bootstrap test** that loads `IntegrationApplication` with both containers and asserts the random HTTP port is reachable.
- [ ] **Step 2: Run the bootstrap test before implementation and confirm the module/container setup fails for the expected missing module or configuration.**
- [ ] **Step 3: Add the root module declaration and E2E dependencies** for Spring Boot test, Testcontainers JUnit, MySQL and Kafka.
- [ ] **Step 4: Implement `E2eApplicationTest` with `@Testcontainers`, `@SpringBootTest(RANDOM_PORT)`, `@DynamicPropertySource`, `@ActiveProfiles("e2e")`, and an explicit Kafka topic property.
- [ ] **Step 5: Run the bootstrap test and verify it passes when Docker is available; record the exact Docker daemon error if unavailable.**
- [ ] **Step 6: Commit `test: bootstrap deterministic mysql kafka e2e module`.**

### Task 3: Implement HTTP client and Kafka event observer

**Files:**
- Create: `e2e/src/test/java/com/cl2/integration/e2e/ApiClient.java`
- Create: `e2e/src/test/java/com/cl2/integration/e2e/IntegrationProfilePayloads.java`
- Create: `e2e/src/test/java/com/cl2/integration/e2e/KafkaEventObserver.java`
- Test: `e2e/src/test/java/com/cl2/integration/e2e/KafkaEventObserverTest.java`

**Interfaces:**
- `ApiClient.create(UUID tenantId, String domain, String source)` returns an HTTP response DTO and sends `X-Tenant-ID`.
- `ApiClient.list(UUID tenantId, boolean activeOnly)`, `get`, `update`, and `deactivate` map the existing REST paths and status codes.
- `KafkaEventObserver.await(UUID profileId, UUID tenantId, String eventType, Duration timeout)` returns the decoded event or throws an assertion failure containing the filter criteria.

- [ ] **Step 1: Write the failing observer test** using an in-memory `ConsumerRecord` sequence to prove unrelated tenant/profile/type messages are skipped and a matching message is returned before timeout.
- [ ] **Step 2: Run the observer test and verify the expected missing-class failure.**
- [ ] **Step 3: Implement the observer with a dedicated consumer group, `poll(Duration)`, a deadline based on `System.nanoTime()`, JSON decoding and `close()` in `AutoCloseable` cleanup.**
- [ ] **Step 4: Implement `ApiClient` using `TestRestTemplate` and the existing request/response JSON contract; never put tenant IDs in request bodies.**
- [ ] **Step 5: Run observer and compile tests, then commit `test: add e2e api and kafka helpers`.**

### Task 4: Add the two-tenant integration-profile scenarios

**Files:**
- Create: `e2e/src/test/java/com/cl2/integration/e2e/IntegrationProfileE2ETest.java`
- Modify: `e2e/src/test/resources/application-e2e.yml`

**Interfaces:**
- Test class extends `E2eApplicationTest`, uses `ApiClient` and `KafkaEventObserver`, and generates two unique tenant IDs per test.
- Every Kafka assertion identifies the profile and tenant and uses a bounded timeout such as `Duration.ofSeconds(15)`.

- [ ] **Step 1: Write the failing E2E scenario** for create/list/get, cross-tenant `404`, update with optimistic version, deactivate, active-only filtering and historical listing.
- [ ] **Step 2: Add event assertions** for created, updated and deactivated events, checking event tenant/profile IDs and current active/version state.
- [ ] **Step 3: Run the scenario against the empty environment and verify it fails clearly when the app/module/event publication is incomplete.**
- [ ] **Step 4: Implement only test wiring or production corrections proven necessary by the failing scenario; do not weaken assertions or add sleeps.**
- [ ] **Step 5: Run `mvn -pl e2e -am -Dtest=IntegrationProfileE2ETest test` and verify all tenant and Kafka assertions pass with Docker.**
- [ ] **Step 6: Commit `test: cover end to end tenant kafka flow`.**

### Task 5: Verify clean reproducibility and document execution

**Files:**
- Modify: `README.md`
- Modify: `compose.yaml` only if the E2E topic/bootstrap configuration needs an explicit compatible setting.
- Create: `e2e/README.md`

- [ ] **Step 1: Document the exact commands** for `docker compose config`, `mvn -pl e2e -am test`, targeted E2E execution, Docker troubleshooting and cleanup.
- [ ] **Step 2: Run the application unit/web suite and the E2E module from clean containers.**
- [ ] **Step 3: Remove containers/volumes with `docker compose down -v` only for the test project and rerun the E2E suite to verify Flyway/Kafka initialization from empty state.**
- [ ] **Step 4: Run `git diff --check`, inspect `git status --short`, and confirm no credentials or tokens are tracked.**
- [ ] **Step 5: Commit `docs: document deterministic kafka api e2e workflow`.**

## Final Verification

- `mvn -q test` passes for the application module.
- `mvn -q -pl e2e -am test` passes when Docker is available.
- `docker compose config --quiet` passes.
- The E2E observer has finite timeout behavior and no fixed sleeps.
- A clean container run applies Flyway and receives matching Kafka events for both tenants.
