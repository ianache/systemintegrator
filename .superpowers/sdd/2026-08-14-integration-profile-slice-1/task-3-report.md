# Task 3 Report: Integration Profile Domain Model and Repository Port

## Status

Completed.

## Changed files

- `src/main/java/com/cl2/integration/domain/model/IntegrationProfile.java`
- `src/main/java/com/cl2/integration/domain/model/SourceOfTruth.java`
- `src/main/java/com/cl2/integration/domain/model/SyncDirection.java`
- `src/main/java/com/cl2/integration/domain/port/IntegrationProfileRepository.java`
- `src/main/java/com/cl2/integration/application/exception/IntegrationProfileConflictException.java`
- `src/main/java/com/cl2/integration/application/exception/IntegrationProfileNotFoundException.java`
- `src/test/java/com/cl2/integration/domain/model/IntegrationProfileTest.java`

## Commit

- `413b0e6927276d3c2876bc29a04eaebf526b15be` — `feat: add integration profile domain`

## TDD evidence

1. Added `IntegrationProfileTest` before production code.
2. Ran `mvn test -Dtest=IntegrationProfileTest` before implementation. After Maven dependencies were available, it failed at test compilation because `IntegrationProfile`, `SyncDirection`, and `SourceOfTruth` did not exist.
3. Implemented the smallest domain model, enums, repository port, and required application exceptions to satisfy the specified behavior.
4. Re-ran `mvn test -Dtest=IntegrationProfileTest`: 8 tests run, 0 failures, 0 errors.

## Verification

- `mvn test -Dtest=IntegrationProfileTest` — PASS: 8 tests, 0 failures, 0 errors, 0 skipped.
- `mvn test` — PASS: 20 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --cached --check` — PASS before commit.
- Domain import scan for Spring, JPA, and HTTP packages — no matches.

## Self-review

- Immutable profile state is preserved: updates and deactivation return a new profile; repeated deactivation returns the existing inactive profile.
- Creation and updates enforce required IDs, nonblank domain/source fields, and non-null enums at the domain boundary.
- Version increments on a successful update or first deactivation and rejects mismatched expected versions.
- Every repository lookup and uniqueness operation explicitly accepts a tenant ID. The port declares no physical delete operation.
- Changes are restricted to the Task 3 source/test files listed above.

## Concerns

- The full existing test suite emits pre-existing Mockito dynamic-agent/JDK warnings. All tests pass; no Task 3 behavior is affected.

## Fix Round 1

### Changes

- Replaced `SourceOfTruth.INTERNAL` with the approved `PLATFORM` value and added `SHARED`, leaving the enum with exactly `PLATFORM`, `EXTERNAL`, and `SHARED`.
- Updated profile creation test fixtures and assertions to use `PLATFORM`; added a test that verifies the complete, ordered enum contract.
- Changed optimistic-version mismatch handling in `IntegrationProfile.update` from `IllegalStateException` to `IntegrationProfileConflictException`.
- Updated the mismatch test to assert the typed conflict exception.

### TDD and verification

1. Updated the domain tests first. The first focused run failed to compile because `PLATFORM` and `SHARED` were absent.
2. Added the required enum values. The next focused run failed because the implementation threw `IllegalStateException` instead of `IntegrationProfileConflictException`.
3. Changed the mismatch exception type and reran the focused suite: 9 tests passed with 0 failures or errors.
4. Ran `mvn test`: 21 tests passed with 0 failures or errors.
5. Re-ran the domain forbidden-import scan: no Spring, JPA, or HTTP imports found. `git diff --check` passed.
