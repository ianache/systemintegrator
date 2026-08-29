# Task 1 Report

## Changed files

- `application/src/main/java/com/cl2/integration/adapter/out/generic/model/ExtractionConfig.java`
  - Added `batchMode` and `batchSize` after `watermarkColumn`.
  - Normalizes absent/null `batchMode` to `false` and null, zero, or negative `batchSize` to `500`.
  - Retained a twelve-argument constructor for existing callers.
- `application/src/main/java/com/cl2/integration/integration/batch/BatchContext.java`
  - Added immutable batch metadata record and `unitary()`/`batch(int)` factories.
- `application/src/test/java/com/cl2/integration/adapter/out/generic/model/ExtractionConfigTest.java`
  - Added coverage for defaults, invalid size normalization, and explicit batch configuration.
- `application/src/test/java/com/cl2/integration/integration/batch/BatchContextTest.java`
  - Added coverage for unitary and valid batch factories and invalid size rejection.

## TDD red phase

Command:

```text
mvn -pl application '-Dtest=ExtractionConfigTest,BatchContextTest' test
```

Output:

```text
BUILD FAILURE
COMPILATION ERROR
cannot find symbol: method batchMode()
cannot find symbol: method batchSize()
cannot find symbol: class BatchContext
```

The failure was caused by the requested production API not yet existing.

## TDD green phase

Command:

```text
mvn -pl application '-Dtest=ExtractionConfigTest,BatchContextTest' test
```

Output:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- ExtractionConfigTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- BatchContextTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`git diff --check` passed; Git reported only the repository's LF/CRLF normalization warnings.

## Commit

Implementation commit hash: `f5466ee`.

## Concerns

The focused Maven run reports pre-existing compiler deprecation warnings for `MockBean` and `SqlSecurityValidator`; they do not affect this task's tests.
