# Task 1 Report — Publish integration-profile events after commit

## Status

Implemented the requested production event contract and event publication flow. The focused Maven test run compiles production code but remains red because the supplied transaction and publisher tests contain independent test-harness defects described below.

## Changed files

- `src/main/java/com/cl2/integration/application/IntegrationProfileService.java`
  - Injects `ApplicationEventPublisher`.
  - Emits created, updated, and deactivated `IntegrationProfileEvent` instances after successful repository saves.
- `src/main/java/com/cl2/integration/integration/profile/IntegrationProfileEvent.java`
  - Immutable event record with ID, type, profile ID, tenant ID, UTC `Instant`, and current `IntegrationProfileView` state.
- `src/main/java/com/cl2/integration/integration/profile/IntegrationProfileEventPublisher.java`
  - Serializes events as ISO-8601 JSON and sends them to `integration-profile.events` using the profile ID as key.
- `src/main/java/com/cl2/integration/integration/profile/IntegrationProfileEventListener.java`
  - Forwards events to the Kafka publisher with `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`.
- `src/test/java/com/cl2/integration/integration/profile/IntegrationProfileEventPublisherTest.java`
  - Verifies the Kafka topic/key and complete serialized event state.
- `src/test/java/com/cl2/integration/application/IntegrationProfileEventTransactionTest.java`
  - Verifies commit-only publication, rollback suppression, and exact created/updated/deactivated states.
- `src/test/java/com/cl2/integration/application/IntegrationProfileServiceTest.java`
  - Supplies the service's application-event publisher dependency while preserving existing service coverage.

Existing uncommitted test changes were preserved unchanged.

## Commands and results

1. `mvn '-Dtest=IntegrationProfileEventPublisherTest,IntegrationProfileEventTransactionTest' test`
   - RED confirmed: test compilation failed because `IntegrationProfileEvent`, `IntegrationProfileEventPublisher`, `IntegrationProfileEventListener`, and the two-argument `IntegrationProfileService` constructor did not exist.

2. `mvn '-Dtest=IntegrationProfileEventPublisherTest,IntegrationProfileEventTransactionTest,IntegrationProfileServiceTest' test`
   - Production compilation succeeded: 47 main source files compiled.
   - Focused tests: 13 run; `IntegrationProfileServiceTest` passed all 9 tests; the transaction test had 3 failures and the publisher test had 1 Mockito error.

3. `git diff --check`
   - Exit code 0; no whitespace errors reported.

## Round 1 Fix Report

Reviewer fixes applied:

- Replaced the transaction test manager's stateless transaction lookup with a thread-bound transaction resource and `isExistingTransaction` implementation. Service calls now join the outer `TransactionTemplate` transaction, so the `AFTER_COMMIT` listener runs after the callback and rollback prevents publication.
- Wrapped the publisher verification topic and key with `eq(...)`, allowing Mockito to capture and inspect the JSON payload.
- Added exact profile ID, tenant, domain/source, active flag, and version assertions for created, updated, and deactivated events.
- Added both event test files and the modified `IntegrationProfileServiceTest` to the changed-files report.

Fix verification commands and results:

4. `mvn -q '-Dtest=IntegrationProfileEventPublisherTest,IntegrationProfileEventTransactionTest,IntegrationProfileServiceTest' test`
   - Exit code 0.
   - Tests run: 13; failures: 0; errors: 0; skipped: 0.
   - Maven emitted the existing Mockito/Byte Buddy dynamic-agent warnings on Java 21; no test failures or production errors were reported.

5. `git diff --check`
   - Exit code 0; no whitespace errors reported.

## Concerns

1. `IntegrationProfileEventTransactionTest.TestTransactionManager` does not report an existing outer transaction. Spring consequently starts and commits an inner service transaction before the test sets `callbackCompleted`; the listener is correctly invoked after that inner commit, but the test asserts it should wait for the outer `TransactionTemplate` callback. A transaction manager that recognizes the existing transaction, or an integration test using Spring's standard transaction infrastructure, is needed to validate the intended outer-transaction behavior.

2. `IntegrationProfileEventPublisherTest` calls `verify(kafkaTemplate).send("integration-profile.events", PROFILE_ID.toString(), payload.capture())`. Mockito does not allow one captor matcher mixed with two raw arguments, so it throws `InvalidUseOfMatchersException` before it can inspect the produced JSON. The test must wrap the first two arguments with `eq(...)` (or capture all three) to assert the required Kafka key and payload.

3. Maven emitted existing JVM/Mockito dynamic-agent warnings. They do not affect compilation, but the build should eventually configure Mockito as a Java agent for newer JDK behavior.
