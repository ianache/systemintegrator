# Task 4 Report — REST Routing Through Sync Orchestrator

Date: 2026-08-26
Worktree: `C:\Users\ianache\Desktop\DATA\01-DOCUMENTOS\02-PROYECTOS\04-CL2\08-Integration\.worktrees\generic-rest-inbound-adapter`

## Scope completed

- Added failing orchestrator tests first for REST routing, JDBC preservation, unsupported protocol handling, missing REST key handling, and REST failure watermark safety.
- Implemented protocol strategy selection in `IntegrationSyncOrchestrator` with minimal changes.
- Preserved the existing JDBC extraction path, transformation flow, duplicate detection, outbox persistence, sync-state handling, metrics, tenant flow, and cancellation behavior.
- Kept `GenericRestAdapter` and DTOs unchanged.

## Files changed

- `application/src/main/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestrator.java`
- `application/src/test/java/com/cl2/integration/integration/sync/IntegrationSyncOrchestratorTest.java`

## Command log

### 1. Initial focused test run in sandbox

Command:

```powershell
mvn -pl application -Dtest=IntegrationSyncOrchestratorTest test
```

Output:

```text
Acceso denegado.
[INFO] Scanning for projects...
Downloading from central: https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/3.4.5/spring-boot-starter-parent-3.4.5.pom
[ERROR] [ERROR] Some problems were encountered while processing the POMs:
[FATAL] Non-resolvable parent POM for com.cl2:integration-parent:0.0.1-SNAPSHOT: The following artifacts could not be resolved: org.springframework.boot:spring-boot-starter-parent:pom:3.4.5 (absent): Could not transfer artifact org.springframework.boot:spring-boot-starter-parent:pom:3.4.5 from/to central (https://repo.maven.apache.org/maven2): Permission denied: getsockopt and 'parent.relativePath' points at no local POM @ line 7, column 13
[ERROR] The build could not read 1 project
```

Result: blocked by sandboxed dependency download, reran with broader access.

### 2. RED verification after adding tests

Command:

```powershell
mvn -pl application -Dtest=IntegrationSyncOrchestratorTest test
```

Output excerpt:

```text
[INFO] Running com.cl2.integration.integration.sync.IntegrationSyncOrchestratorTest
[ERROR] Tests run: 12, Failures: 4, Errors: 0, Skipped: 0, Time elapsed: 2.239 s <<< FAILURE! -- in com.cl2.integration.integration.sync.IntegrationSyncOrchestratorTest
[ERROR] com.cl2.integration.integration.sync.IntegrationSyncOrchestratorTest.restAdapterFailureRecordsFailureWithoutPersistingSuccessfulWatermark -- Time elapsed: 0.028 s <<< FAILURE!
java.lang.AssertionError:
Expecting code to raise a throwable.

[ERROR] com.cl2.integration.integration.sync.IntegrationSyncOrchestratorTest.unsupportedProtocolFailsExplicitlyBeforeWritingEvents -- Time elapsed: 0.013 s <<< FAILURE!
java.lang.AssertionError:
Expecting code to raise a throwable.

[ERROR] com.cl2.integration.integration.sync.IntegrationSyncOrchestratorTest.restProfilesDelegateToGenericRestAdapterAndSkipJdbcExtraction -- Time elapsed: 0.040 s <<< FAILURE!
Wanted but not invoked:
genericRestAdapter.extract(...)
Actually, there were zero interactions with this mock.

[ERROR] com.cl2.integration.integration.sync.IntegrationSyncOrchestratorTest.missingRestKeyFailsBeforeOutboxSave -- Time elapsed: 0.012 s <<< FAILURE!
java.lang.AssertionError:
Expecting code to raise a throwable.

[INFO] BUILD FAILURE
```

Result: confirmed RED because the orchestrator still always used the JDBC path.

### 3. First post-implementation focused test run

Command:

```powershell
mvn -pl application -Dtest=IntegrationSyncOrchestratorTest test
```

Output excerpt:

```text
[ERROR] COMPILATION ERROR :
[ERROR] ...IntegrationSyncOrchestratorTest.java:[127,20] no suitable constructor found for IntegrationSyncOrchestrator(...)
[INFO] BUILD FAILURE
```

Result: restored the pre-existing metrics-aware constructor overload as a compatibility fallback.

### 4. Second post-implementation focused test run

Command:

```powershell
mvn -pl application -Dtest=IntegrationSyncOrchestratorTest test
```

Output excerpt:

```text
[ERROR] Tests run: 12, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 2.342 s <<< FAILURE! -- in com.cl2.integration.integration.sync.IntegrationSyncOrchestratorTest
[ERROR] com.cl2.integration.integration.sync.IntegrationSyncOrchestratorTest.restProfilesDelegateToGenericRestAdapterAndSkipJdbcExtraction -- Time elapsed: 0.022 s <<< ERROR!
com.cl2.integration.integration.sync.IntegrationSyncException: Sync failed for profile ...
Caused by: com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 date/time type `java.time.Instant` not supported by default
[INFO] BUILD FAILURE
```

Result: tightened REST test fixtures to use `Timestamp` so the test exercised REST routing and key handling instead of `ObjectMapper` limitations.

### 5. Final GREEN verification

Command:

```powershell
mvn -pl application -Dtest=IntegrationSyncOrchestratorTest test
```

Output excerpt:

```text
[INFO] Running com.cl2.integration.integration.sync.IntegrationSyncOrchestratorTest
...
00:02:31.058 [main] WARN com.cl2.integration.integration.sync.IntegrationSyncOrchestrator -- Sync run failed for profile ...: REST adapter boom
00:02:31.075 [main] WARN com.cl2.integration.integration.sync.IntegrationSyncOrchestrator -- Sync run failed for profile ...: Unsupported integration protocol for sync orchestration: SOAP
00:02:31.136 [main] WARN com.cl2.integration.integration.sync.IntegrationSyncOrchestrator -- Sync run failed for profile ...: connection refused
00:02:31.182 [main] WARN com.cl2.integration.integration.sync.IntegrationSyncOrchestrator -- Sync run failed for profile ...: REST row is missing keyProperty 'externalId'
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.434 s -- in com.cl2.integration.integration.sync.IntegrationSyncOrchestratorTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] BUILD SUCCESS
```

Result: GREEN.

## Implementation summary

### `IntegrationSyncOrchestrator`

- Injected `GenericRestAdapter` while preserving compatibility constructors.
- Selected extraction strategy by `IntegrationProtocol` after reading extraction config, resolving secrets, and reading the existing watermark.
- Kept JDBC extraction on the original `JdbcDataSourceFactory` + `GenericJdbcAdapter` path.
- Routed REST extraction through `ResilienceExecutor.execute(profile.tenantId(), profile.configuration().connector(), ...)`.
- Added explicit unsupported-protocol failure before outbox writes.
- Switched aggregate key selection to:
  - `keyColumn` for JDBC
  - `keyProperty` for REST
- Added a REST-only guard that fails when the configured REST key is missing/null in a row before any outbox write.

### `IntegrationSyncOrchestratorTest`

- Added reflective constructor setup so the tests can exercise the REST-aware constructor when present while still reproducing RED against the old JDBC-only shape.
- Added focused tests for:
  - REST adapter delegation and JDBC bypass
  - JDBC adapter preservation
  - unsupported protocol failure
  - missing REST key failure before outbox save
  - REST adapter failure without successful watermark persistence

## Notes / concerns

- The focused suite passes, but Maven still emits existing unrelated warnings about deprecated `@MockBean` usage and dynamic Mockito agent loading.
- There were already unrelated untracked files in this worktree:
  - `docs/superpowers/plans/2026-08-25-generic-rest-inbound-adapter.md`
  - `docs/superpowers/specs/2026-08-25-generic-rest-inbound-adapter-design.md`
  They were intentionally left untouched.
