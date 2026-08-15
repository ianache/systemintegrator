# Extended IntegrationProfile Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `IntegrationProfile` with declarative connector, endpoint, credential-reference, mapping, transformation, synchronization, retry and rate-limit configuration while preserving legacy profiles.

**Architecture:** Keep the domain model immutable and tenant-agnostic beyond its existing `tenantId`. Add a small value object for the optional connection configuration, transport it through application commands and HTTP DTOs, and persist scalar values plus validated JSON documents in nullable MySQL columns. Runtime connector execution, Vault lookup, retries and rate limiting remain outside this plan.

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Data JPA, Hibernate, Flyway, MySQL 8.4, Jackson, JUnit 5, Spring MVC Test, Testcontainers, Maven.

## Global Constraints

- Preserve payloads that omit all new fields.
- Persist enums as strings and JSON documents as MySQL `JSON` columns.
- Never accept or persist a plaintext credential; only `credentialRef` is allowed.
- Tenant identity continues to come from `TenantContext`; no request field may set it.
- Every production behavior change must have a failing test before implementation.
- Do not add SAP/SIGO execution, Vault integration, Outbox dispatch, Inbox consumption, DLQ, Resilience4j or rate-limit runtime behavior in this plan.

---

### Task 1: Add the immutable configuration model and domain behavior

**Files:**
- Create: `src/main/java/com/cl2/integration/domain/model/IntegrationProtocol.java`
- Create: `src/main/java/com/cl2/integration/domain/model/IntegrationProfileConfiguration.java`
- Modify: `src/main/java/com/cl2/integration/domain/model/IntegrationProfile.java`
- Modify: `src/test/java/com/cl2/integration/domain/model/IntegrationProfileTest.java`

**Interfaces:**
- `IntegrationProtocol` exposes exactly `REST`, `SOAP`, `JSON_RPC`, `KAFKA`, `JDBC`.
- `IntegrationProfileConfiguration` exposes nullable `protocol`, `connector`, `adapter`, `endpoint`, `credentialRef`, `mapping`, `transformation`, `syncPolicy`, `retryPolicy`, and `rateLimitPolicy`.
- JSON fields use canonical `String` values after parsing; `null` means not configured.
- `IntegrationProfile.create(...)`, `rehydrate(...)`, and `update(...)` accept a configuration object; `IntegrationProfile.configuration()` returns it.

- [ ] **Step 1: Write failing domain tests**

Add tests that assert:

```java
@Test
void createsAProfileWithConnectorConfiguration() {
    IntegrationProfileConfiguration configuration = configuration();

    IntegrationProfile profile = IntegrationProfile.create(
            PROFILE_ID, TENANT_ID, "orders", "erp", SyncDirection.INBOUND,
            SourceOfTruth.PLATFORM, configuration);

    assertThat(profile.configuration()).isEqualTo(configuration);
}

@Test
void rejectsAConfiguredProtocolWithoutConnectorAndAdapter() {
    assertThatThrownBy(() -> new IntegrationProfileConfiguration(
            IntegrationProtocol.REST, null, "adapter", null, null,
            null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
}
```

Use a helper with valid values, including `retryPolicy` JSON containing `maxAttempts: 3` and `initialBackoffMs: 100` and `rateLimitPolicy` JSON containing `requestsPerSecond: 10`.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\mvnw.cmd -q -Dtest=IntegrationProfileTest test
```

Expected: compilation/test failure because the configuration type and new factory signatures do not yet exist.

- [ ] **Step 3: Implement the minimal model**

Implement the enum and immutable configuration value object. Validate only domain invariants that do not require JSON parsing:

- configured `protocol` requires nonblank `connector` and `adapter`;
- `credentialRef`, when present, must be nonblank;
- no configuration field may contain a plaintext field named `password` because only a reference is modeled.

Update `IntegrationProfile` constructors, factories, `update`, and accessors while preserving `create`/`rehydrate` overloads only if existing tests need source compatibility.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the same Maven command. Expected: all `IntegrationProfileTest` tests pass.

- [ ] **Step 5: Commit the domain change**

```powershell
git add src/main/java/com/cl2/integration/domain/model src/test/java/com/cl2/integration/domain/model/IntegrationProfileTest.java
git commit -m "feat: add integration profile connector configuration"
```

### Task 2: Extend application commands and HTTP contracts with validation

**Files:**
- Create: `src/main/java/com/cl2/integration/adapter/in/web/dto/IntegrationProfileConfigurationRequest.java`
- Modify: `src/main/java/com/cl2/integration/adapter/in/web/dto/CreateIntegrationProfileRequest.java`
- Modify: `src/main/java/com/cl2/integration/adapter/in/web/dto/UpdateIntegrationProfileRequest.java`
- Modify: `src/main/java/com/cl2/integration/adapter/in/web/dto/IntegrationProfileResponse.java`
- Modify: `src/main/java/com/cl2/integration/application/command/CreateIntegrationProfileCommand.java`
- Modify: `src/main/java/com/cl2/integration/application/command/UpdateIntegrationProfileCommand.java`
- Modify: `src/main/java/com/cl2/integration/application/IntegrationProfileService.java`
- Modify: `src/main/java/com/cl2/integration/adapter/in/web/IntegrationProfileController.java`
- Test: `src/test/java/com/cl2/integration/adapter/in/web/IntegrationProfileControllerTest.java`
- Test: `src/test/java/com/cl2/integration/application/IntegrationProfileServiceTest.java`

**Interfaces:**
- Request configuration fields are nullable and named `protocol`, `connector`, `adapter`, `endpoint`, `credentialRef`, `mapping`, `transformation`, `syncPolicy`, `retryPolicy`, and `rateLimitPolicy`.
- JSON request fields use `JsonNode` so malformed JSON is rejected by Jackson before the service.
- `IntegrationProfileConfigurationRequest` maps to the domain configuration using a single conversion method that serializes non-null JSON nodes with the injected `ObjectMapper`.
- Responses expose the configuration but never expose a secret value.

- [ ] **Step 1: Write failing controller tests**

Add tests for:

```java
@Test
void createsAndReturnsAnExtendedProfile() throws Exception {
    mockMvc.perform(post("/api/v1/integration-profiles")
            .header("X-Tenant-ID", TENANT_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"businessDomain":"orders","externalSource":"erp",
                 "syncDirection":"INBOUND","sourceOfTruth":"PLATFORM",
                 "protocol":"REST","connector":"sigo",
                 "adapter":"sigo-vehicle-http","endpoint":"https://sigo.test/api",
                 "credentialRef":"secret/sigo/orders",
                 "mapping":{"vin":"vehicle.vin"},
                 "retryPolicy":{"maxAttempts":3,"initialBackoffMs":100},
                 "rateLimitPolicy":{"requestsPerSecond":10}}
                """)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.configuration.protocol").value("REST"))
            .andExpect(jsonPath("$.configuration.credentialRef").value("secret/sigo/orders"))
            .andExpect(jsonPath("$.configuration.mapping.vin").value("vehicle.vin"));
}

@Test
void acceptsALegacyProfileWithoutConfiguration() throws Exception {
    mockMvc.perform(post("/api/v1/integration-profiles")
            .header("X-Tenant-ID", TENANT_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"businessDomain":"orders","externalSource":"erp",
                 "syncDirection":"INBOUND","sourceOfTruth":"PLATFORM"}
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.configuration.protocol").doesNotExist());
}

@Test
void rejectsMalformedConfigurationJson() throws Exception {
    mockMvc.perform(post("/api/v1/integration-profiles")
            .header("X-Tenant-ID", TENANT_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"businessDomain":"orders","externalSource":"erp",
                 "syncDirection":"INBOUND","sourceOfTruth":"PLATFORM",
                 "mapping":{"vin":}}
                """))
            .andExpect(status().isBadRequest());
}
```

Add service tests proving a configured protocol without connector/adapter is rejected before persistence and that update increments the version while replacing configuration.

- [ ] **Step 2: Run focused web and service tests and verify RED**

```powershell
.\mvnw.cmd -q -Dtest=IntegrationProfileControllerTest,IntegrationProfileServiceTest test
```

Expected: compilation failures or assertion failures for the absent configuration contract.

- [ ] **Step 3: Implement DTO, command and service mapping**

Add the nullable request record, map it to `IntegrationProfileConfiguration`, pass it through create/update commands, and include it in `IntegrationProfileResponse`. Use Jackson parsing as the JSON syntax boundary and preserve the existing Problem Details handler for malformed bodies and validation failures.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the same Maven command. Expected: all existing and new controller/service tests pass.

- [ ] **Step 5: Commit the HTTP/application change**

```powershell
git add src/main/java/com/cl2/integration/adapter src/main/java/com/cl2/integration/application src/test/java/com/cl2/integration/adapter src/test/java/com/cl2/integration/application
git commit -m "feat: expose integration profile configuration API"
```

### Task 3: Persist and rehydrate configuration with Flyway and JPA

**Files:**
- Create: `src/main/resources/db/migration/V3__add_integration_profile_configuration.sql`
- Modify: `src/main/java/com/cl2/integration/adapter/out/persistence/IntegrationProfileJpaEntity.java`
- Modify: `src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfilePersistenceAdapterTest.java`
- Modify: `src/test/java/com/cl2/integration/adapter/out/persistence/IntegrationProfileJpaEntityTest.java`

**Interfaces:**
- Migration adds nullable columns: `protocol VARCHAR(20)`, `connector VARCHAR(100)`, `adapter VARCHAR(100)`, `endpoint VARCHAR(500)`, `credential_ref VARCHAR(255)`, and nullable MySQL `JSON` columns `mapping_json`, `transformation_json`, `sync_policy_json`, `retry_policy_json`, `rate_limit_policy_json`.
- JPA maps enums with `EnumType.STRING` and JSON columns as nullable strings using the existing entity style.
- Rehydration returns `IntegrationProfileConfiguration` with `null` for every legacy column.

- [ ] **Step 1: Write failing persistence tests**

Add a Testcontainers test that saves a fully configured profile, reads it back, and asserts all scalar and JSON values are unchanged. Add a migration assertion for all new columns. Add a legacy-row test that asserts null configuration values remain null.

- [ ] **Step 2: Run the focused persistence tests and verify RED**

```powershell
.\mvnw.cmd -q -Dtest=IntegrationProfilePersistenceAdapterTest,IntegrationProfileJpaEntityTest test
```

Expected: failure because V3 and entity mappings are absent.

- [ ] **Step 3: Add V3 and entity mapping**

Create the nullable columns, update entity constructors/factories/converters, and pass the configuration through `toDomain()` and `from()`. Do not add a database index or uniqueness rule involving configuration fields.

- [ ] **Step 4: Run focused persistence tests and verify GREEN**

Run the same command and confirm Flyway applies V1, V2 and V3 and all assertions pass.

- [ ] **Step 5: Commit persistence changes**

```powershell
git add src/main/resources/db/migration/V3__add_integration_profile_configuration.sql src/main/java/com/cl2/integration/adapter/out/persistence src/test/java/com/cl2/integration/adapter/out/persistence
git commit -m "feat: persist integration profile configuration"
```

### Task 4: Verify full compatibility and update API documentation

**Files:**
- Modify: `docs/test-cases/test-cases-manual-e2e.md`
- Modify: `README.md`
- Modify: `docs/superpowers/reports/2026-08-14-sigo-vehicle-mvp-report.md` only if the existing status references become inaccurate
- Test: `src/test/java/com/cl2/integration/IntegrationProfileEndToEndTest.java`
- Test: `e2e/src/test/java/com/cl2/integration/e2e/IntegrationProfileE2ETest.java`

- [ ] **Step 1: Add end-to-end compatibility scenarios**

Add one legacy create/update lifecycle and one configured profile lifecycle. Assert the response has the configuration, the tenant remains the authenticated/header tenant, and an update with an old `expectedVersion` still returns the existing conflict response.

- [ ] **Step 2: Run the complete unit/integration suite**

```powershell
.\mvnw.cmd -q test
```

Expected: exit code 0 with all existing tests and new tests passing.

- [ ] **Step 3: Run the Docker/Testcontainers E2E module**

```powershell
.\mvnw.cmd -q -pl e2e -am test
```

Expected: MySQL/Kafka containers start, Flyway applies V3, the lifecycle and Kafka assertions pass, and the test process exits 0.

- [ ] **Step 4: Document the extended request and security rule**

Add a PowerShell example using `curl.exe` with escaped JSON quotes, show `credentialRef` instead of a password, document the legacy payload, and state that runtime retry/rate limiting are not activated by configuration alone.

- [ ] **Step 5: Run repository checks**

```powershell
git diff --check
git status --short
```

Confirm no token, client secret, endpoint credential, or generated container artifact is tracked.

- [ ] **Step 6: Commit documentation and final tests**

```powershell
git add README.md docs/test-cases/test-cases-manual-e2e.md src/test/java/com/cl2/integration/IntegrationProfileEndToEndTest.java e2e/src/test/java/com/cl2/integration/e2e/IntegrationProfileE2ETest.java
git commit -m "test: verify extended integration profile lifecycle"
```

## Completion Criteria

- Legacy profile requests remain valid.
- Extended configuration can be created, read, updated and persisted.
- Invalid connector configuration and malformed JSON are rejected with existing API error conventions.
- Tenant isolation and optimistic versioning remain enforced.
- Full Maven and Testcontainers E2E commands pass with fresh V3 migration state.
- No runtime connector execution is claimed by this phase.
