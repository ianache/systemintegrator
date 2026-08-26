# Generic REST Inbound Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add REST polling for inbound integration profiles while preserving the existing JDBC synchronization path and transactional outbox behavior.

**Architecture:** Add a `GenericRestAdapter` that builds authenticated JSON HTTP requests from `ExtractionConfig`, extracts records through JSONPath, and returns normalized maps. Update `IntegrationSyncOrchestrator` to select JDBC or REST extraction from the profile protocol, while keeping transformation, duplicate detection, watermark handling, outbox persistence, resilience, metrics, and failure recording in the orchestrator.

**Tech Stack:** Java 21, Spring Boot 3, Spring `RestClient`, Jackson, Jayway JSONPath, WireMock, JUnit 5, Mockito, Maven.

**Spec:** `docs/superpowers/specs/2026-08-25-generic-rest-inbound-adapter-design.md`

## Global Constraints

- Preserve tenant isolation: every secret lookup, resilience call, sync-state lookup, and outbox write uses the profile tenant.
- Preserve `GenericJdbcAdapter` behavior for JDBC profiles.
- Use `RestClient`; do not introduce another HTTP client library.
- Do not log tokens, passwords, API keys, or full sensitive payloads.
- Do not advance the watermark unless the existing orchestration transaction completes successfully.
- Do not add database migrations or change the public profile JSON contract.
- Follow TDD: each production behavior starts with a failing test and is implemented minimally.
- Keep REST pagination out of this slice; only configured request parameters and response extraction are supported.

---

### Task 1: Define the REST adapter contract and failing request tests

**Files:**
- Create: `application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java`
- Inspect: `application/src/main/java/com/cl2/integration/adapter/out/generic/model/ExtractionConfig.java`
- Inspect: `application/src/main/java/com/cl2/integration/integration/security/ResolvedSecret.java`

**Interfaces:**
- Consumes: `ExtractionConfig`, `ResolvedSecret`, `Instant`, and an `IntegrationProfile` tenant/connector context.
- Produces: executable expectations for `GenericRestAdapter.extract(...)`.

- [ ] **Step 1: Write the failing test for request construction and watermark substitution.**

Use WireMock to stub `GET /api/customers?updatedSince=2026-01-01T00:00:00Z&limit=100`, return `{"items":[{"id":"c-1"}]}`, and assert that the adapter returns one map. Configure `queryParams` with `updatedSince=:lastSyncWithBuffer`, `limit=100`, `responseJsonPath=$.items[*]`, and `keyProperty=customerId`.

```java
@Test
void extractsRecordsUsingConfiguredPathQueryAndWatermark() {
    wireMock.stubFor(get(urlPathEqualTo("/api/customers"))
        .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
        .withQueryParam("limit", equalTo("100"))
        .willReturn(okJson("{\"items\":[{\"customerId\":\"c-1\"}]}")));

    ExtractionConfig config = new ExtractionConfig(
        null, "updatedSince", null, 100, "GET", "/api/customers",
        Map.of("updatedSince", ":lastSyncWithBuffer", "limit", "100"),
        Map.of(), "$.items[*]", "ISO_8601", "customerId", null);

    List<Map<String, Object>> result = adapter.extract(
        profile("https://localhost:" + wireMockPort()), config,
        ResolvedSecret.bearer("secret/test", "test-token"),
        Instant.parse("2026-01-01T00:00:00Z"));

    assertThat(result).containsExactly(Map.of("customerId", "c-1"));
}
```

- [ ] **Step 2: Run the focused test and verify it fails for the expected missing adapter.**

Run: `mvn -pl application -Dtest=GenericRestAdapterTest test`

Expected: FAIL because `GenericRestAdapter` and its `extract` method do not exist.

- [ ] **Step 3: Add failing authentication tests.**

Add one test per supported mode: Basic sends the expected `Authorization: Basic` header; Bearer sends `Authorization: Bearer test-token`; API Key sends `X-API-Key`; OAuth2 calls the token fetch boundary and sends the returned Bearer token plus configured custom headers. Assert request headers at WireMock, never the internal secret value in logs.

- [ ] **Step 4: Run the focused tests and confirm they fail for the missing implementation.**

Run: `mvn -pl application -Dtest=GenericRestAdapterTest test`

Expected: FAIL with missing `GenericRestAdapter` behavior, not a test setup error.

---

### Task 2: Implement request construction and authentication

**Files:**
- Create: `application/src/main/java/com/cl2/integration/adapter/out/generic/GenericRestAdapter.java`
- Modify: `application/src/main/java/com/cl2/integration/adapter/out/generic/model/ExtractionConfig.java` only if constructor defaults prevent REST use.
- Test: `application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java`

**Interfaces:**
- Consumes: `IntegrationProfile`, `ExtractionConfig`, `ResolvedSecret`, `SecretResolver`-resolved credentials, `OAuth2TokenCacheManager`, and `ObjectMapper`.
- Produces: `List<Map<String, Object>> extract(IntegrationProfile, ExtractionConfig, ResolvedSecret, Instant)`.

- [ ] **Step 1: Implement the smallest adapter constructor and method signature.**

Declare a Spring `@Component` with constructor dependencies `RestClient.Builder`, `ObjectMapper`, `OAuth2TokenCacheManager`, and `Clock` or the application clock abstraction already used by the project. Configure a bounded request timeout through the existing HTTP client configuration pattern.

- [ ] **Step 2: Implement URI and query parameter construction.**

Validate an absolute `http`/`https` endpoint, resolve `config.path`, copy `queryParams`, and replace exact `:lastSyncWithBuffer` values with UTC ISO-8601 text. Do not mutate `ExtractionConfig.queryParams()`.

- [ ] **Step 3: Implement Basic, Bearer, and API Key headers.**

Use Spring request header APIs. Copy custom headers first, then set generated authorization headers so profile-provided `Authorization` cannot override credentials.

- [ ] **Step 4: Implement OAuth2 Client Credentials integration.**

Call the existing `OAuth2TokenCacheManager` using the resolved token URL, client ID, client secret, scope, and profile tenant/connector context. Set the returned access token as Bearer authentication and copy only the resolved custom headers.

- [ ] **Step 5: Run the focused request and authentication tests.**

Run: `mvn -pl application -Dtest=GenericRestAdapterTest test`

Expected: PASS for path, query, watermark, and all four authentication modes.

---

### Task 3: Implement JSON response extraction and validation

**Files:**
- Modify: `application/src/main/java/com/cl2/integration/adapter/out/generic/GenericRestAdapter.java`
- Test: `application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java`

**Interfaces:**
- Consumes: successful JSON response body and `ExtractionConfig.responseJsonPath()`.
- Produces: normalized `List<Map<String, Object>>` or explicit extraction exception.

- [ ] **Step 1: Add failing tests for JSONPath array and root object extraction.**

Verify `$.items[*]` returns multiple maps and `$` returns one map when the root is an object.

- [ ] **Step 2: Add failing tests for malformed and incompatible responses.**

Cover malformed JSON, invalid JSONPath, missing path, scalar path, and a non-2xx response. Assert the exception type/message identifies the extraction failure and that no partial list is returned.

- [ ] **Step 3: Run the tests and verify the new cases fail before implementation.**

Run: `mvn -pl application -Dtest=GenericRestAdapterTest test`

Expected: FAIL because response parsing and validation are not implemented.

- [ ] **Step 4: Implement response parsing and JSONPath extraction.**

Read the body as a Jackson tree, evaluate the JSONPath, accept an array of objects or one root object, convert each object to a `Map<String,Object>`, and reject scalars or missing paths. Wrap parser/JSONPath errors in one explicit adapter exception.

- [ ] **Step 5: Implement HTTP status and endpoint validation.**

Reject non-2xx responses, unsupported methods (`DELETE`, `TRACE`, `CONNECT`), invalid schemes, and missing endpoints before writing any event. Keep error text free of credentials and response bodies that may contain secrets.

- [ ] **Step 6: Run all adapter tests.**

Run: `mvn -pl application -Dtest=GenericRestAdapterTest test`

Expected: PASS with all success and failure cases.

---

### Task 4: Integrate REST extraction into the synchronization orchestrator

**Files:**
- Modify: `application/src/main/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestrator.java`
- Test: `application/src/test/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestratorTest.java`
- Inspect: `application/src/main/java/com/cl2/integration/domain/model/IntegrationProtocol.java`

**Interfaces:**
- Consumes: existing profile lookup, secret resolution, `GenericJdbcAdapter`, new `GenericRestAdapter`, `TransformationService`, `ResilienceExecutor`, `OutboxRepository`, and sync-state services.
- Produces: same canonical event/outbox behavior for JDBC and REST profiles.

- [ ] **Step 1: Add failing test that a REST profile uses `GenericRestAdapter`.**

Build a REST profile with a valid extraction configuration, stub the adapter result, run the orchestrator, and assert transformation and outbox persistence receive the extracted record. Assert the JDBC adapter is not called.

- [ ] **Step 2: Run the focused orchestrator test and verify it fails.**

Run: `mvn -pl application -Dtest=IntegrationSyncOrchestratorTest test`

Expected: FAIL because the orchestrator currently always creates a JDBC data source and invokes `GenericJdbcAdapter`.

- [ ] **Step 3: Refactor extraction behind a private strategy method.**

Resolve `ExtractionConfig` and credentials once. For `JDBC`, preserve the current data source lifecycle and adapter call. For `REST`, call `genericRestAdapter.extract(profile, extractionConfig, secret, watermark)` through `ResilienceExecutor`. For unsupported protocols, throw an explicit `IntegrationSyncException` before event writes.

- [ ] **Step 4: Validate REST record keys before outbox writes.**

Use `keyProperty()` for REST records and retain `keyColumn()` for JDBC records. If the configured key is missing or null, fail the run without writing records and preserve the previous watermark.

- [ ] **Step 5: Add failing regression tests for JDBC preservation and REST failure watermark behavior.**

Verify JDBC profiles still invoke `GenericJdbcAdapter`; verify adapter failure records sync failure and does not call `syncStateRepository.upsert` with a successful watermark.

- [ ] **Step 6: Run orchestrator tests.**

Run: `mvn -pl application -Dtest=IntegrationSyncOrchestratorTest test`

Expected: PASS for REST selection, JDBC preservation, unsupported protocol, missing key, and failure watermark behavior.

---

### Task 5: Add integration coverage and documentation

**Files:**
- Modify: `application/src/test/java/com/cl2/integration/integration/sync/IntegrationSyncEndToEndTest.java` or the existing sync integration test file that owns Testcontainers coverage.
- Modify: `docs/api-rest-adapter.md`
- Modify: `docs/solution_architecture.md` if the current diagram omits REST Inbound.

**Interfaces:**
- Consumes: completed adapter and orchestrator behavior.
- Produces: a reproducible REST Inbound verification and accurate operational documentation.

- [ ] **Step 1: Add a WireMock-backed end-to-end synchronization test.**

Create a REST profile with `responseJsonPath=$.items[*]`, watermark query parameter, and a transformation. Stub the API response, execute the sync, and assert one transformed event is stored in `integration_outbox` with the expected tenant, topic, and payload.

- [ ] **Step 2: Add a failure-path integration test.**

Return HTTP 500 from WireMock, execute the sync, and assert the run is failed, no new outbox event is persisted, and the previous watermark remains unchanged.

- [ ] **Step 3: Run focused integration tests.**

Run: `mvn -pl application -Dtest=IntegrationSyncEndToEndTest test`

Expected: PASS when the required local test dependencies are available; if Testcontainers requires Docker and Docker is unavailable, record the exact skipped command and reason without weakening assertions.

- [ ] **Step 4: Update the REST adapter guide.**

Document the supported inbound methods, watermark substitution, response shapes, required `keyProperty`, authentication behavior, and explicitly state that pagination is not yet supported.

- [ ] **Step 5: Run the complete application test suite.**

Run: `mvn -pl application test`

Expected: all existing tests and new REST tests pass, with any Docker-only failures reported separately.

- [ ] **Step 6: Review the final diff and commit the slice.**

Run: `git diff --check` and `git status --short`.

Commit: `git add application docs/api-rest-adapter.md docs/solution_architecture.md && git commit -m "feat: add generic REST inbound adapter"`

Expected: only the REST adapter implementation, tests, and related documentation are included.

---

## Plan self-review

- Spec coverage: request construction, four authentication modes, JSONPath extraction, HTTP validation, resilience, metrics reuse, watermark safety, JDBC compatibility, tests, and documentation are covered by Tasks 1–5.
- Scope: pagination, new persistence, SAP/SIGO domain work, Backoffice, and Docker topology remain outside this plan.
- Type consistency: the adapter returns `List<Map<String,Object>>`, matching the existing orchestrator row processing; `ExtractionConfig` and `ResolvedSecret` are existing project types.
- No unresolved placeholders or unspecified implementation steps remain.
