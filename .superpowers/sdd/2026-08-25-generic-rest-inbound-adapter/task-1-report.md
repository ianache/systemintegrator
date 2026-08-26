# Task 1 Report

Changed files:

- `application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java`

Exact command:

- `mvn -pl application -Dtest=GenericRestAdapterTest test`

Observed failing output:

```text
[ERROR] /C:/Users/ianache/Desktop/DATA/01-DOCUMENTOS/02-PROYECTOS/04-CL2/08-Integration/.worktrees/generic-rest-inbound-adapter/application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java:[212,13] cannot find symbol
  symbol:   class GenericRestAdapter
  location: class com.cl2.integration.adapter.out.generic.GenericRestAdapterTest
[ERROR] /C:/Users/ianache/Desktop/DATA/01-DOCUMENTOS/02-PROYECTOS/04-CL2/08-Integration/.worktrees/generic-rest-inbound-adapter/application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java:[66,9] cannot find symbol
  symbol:   class GenericRestAdapter
  location: class com.cl2.integration.adapter.out.generic.GenericRestAdapterTest
```

Concerns:

- The focused build required network access to resolve the Maven parent POM before it could reach the expected compile failure.
- The failure is currently a compile-time missing-contract failure, which is the correct Task 1 signal for the absent `GenericRestAdapter` production type.
- No production files were modified.

## Fix Round

Updated to strengthen WireMock verification on the actual observed GET requests and to keep the constructor wiring hidden behind the narrowest possible helper, with OAuth2 left as the only explicit boundary case.

Changed files:

- `application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java`

Exact command:

- `mvn -pl application -Dtest=GenericRestAdapterTest test`

Observed failing output:

```text
[ERROR] /C:/Users/ianache/Desktop/DATA/01-DOCUMENTOS/02-PROYECTOS/04-CL2/08-Integration/.worktrees/generic-rest-inbound-adapter/application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java:[66,9] cannot find symbol
  symbol:   class GenericRestAdapter
  location: class com.cl2.integration.adapter.out.generic.GenericRestAdapterTest
[ERROR] /C:/Users/ianache/Desktop/DATA/01-DOCUMENTOS/02-PROYECTOS/04-CL2/08-Integration/.worktrees/generic-rest-inbound-adapter/application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java:[178,9] cannot find symbol
  symbol:   class GenericRestAdapter
  location: class com.cl2.integration.adapter.out.generic.GenericRestAdapterTest
```

Concerns:

- The build still fails at test compilation because the production `GenericRestAdapter` type is intentionally absent for Task 1.
- The helper still mentions the future adapter constructor implicitly, but only in one place; all behavioral assertions now verify actual GET requests, query parameters, and auth/custom headers through WireMock.

## Fix Round 2

Replaced the per-test adapter wiring with a single test-local `AdapterFactory` so the behavior tests stay focused on the public `extract(...)` contract and the OAuth2 boundary remains the only explicit dependency seam.

Changed files:

- `application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java`

Exact command:

- `mvn -pl application -Dtest=GenericRestAdapterTest test`

Observed failing output:

```text
[ERROR] /C:/Users/ianache/Desktop/DATA/01-DOCUMENTOS/02-PROYECTOS/04-CL2/08-Integration/.worktrees/generic-rest-inbound-adapter/application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java:[67,9] cannot find symbol
  symbol:   class GenericRestAdapter
  location: class com.cl2.integration.adapter.out.generic.GenericRestAdapterTest
[ERROR] /C:/Users/ianache/Desktop/DATA/01-DOCUMENTOS/02-PROYECTOS/04-CL2/08-Integration/.worktrees/generic-rest-inbound-adapter/application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java:[235,24] cannot find symbol
  symbol:   class GenericRestAdapter
  location: class com.cl2.integration.adapter.out.generic.GenericRestAdapterTest.AdapterFactory
```

Concerns:

- The test still cannot compile until the production `GenericRestAdapter` is introduced, which is expected for this task.
- The behavior assertions remain WireMock-backed and still verify query params, auth headers, and OAuth2 token-cache use.

## Fix Round 3

Removed all compile-time coupling to a `GenericRestAdapter` constructor shape by moving adapter creation and `extract(...)` invocation behind a reflective test harness that resolves the class by name and calls the exact public method contract.

Changed files:

- `application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java`

Exact command:

- `mvn -pl application -Dtest=GenericRestAdapterTest test`

Observed failing output:

```text
[ERROR] Tests run: 5, Failures: 0, Errors: 5, Skipped: 0
[ERROR] com.cl2.integration.adapter.out.generic.GenericRestAdapterTest.shouldGetCustomersWithWatermarkSubstitutionLimitAndJsonPathExtraction -- Time elapsed: 0.047 s <<< ERROR!
java.lang.IllegalStateException: Unable to construct com.cl2.integration.adapter.out.generic.GenericRestAdapter
Caused by: java.lang.ClassNotFoundException: com.cl2.integration.adapter.out.generic.GenericRestAdapter
```

Concerns:

- The failure is now an execution-time missing-class failure rather than a compile-time failure, which is expected after removing direct type coupling from the test.
- HTTP behavior remains real and WireMock-backed; the harness only affects how the absent adapter is located and invoked.

## Fix Round 4

Replaced the reflective constructor-matching harness with a Spring-backed test context that resolves the production adapter bean by type name, while the tests themselves call only the public `extract(IntegrationProfile, ExtractionConfig, ResolvedSecret, Instant)` contract. The OAuth2 token-cache boundary remains mocked, WireMock still verifies the real HTTP requests, and the test clock is intentionally different from the watermark argument so the contract still guards the method input rather than any injected time source.

Changed files:

- `application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java`

Exact command:

- `mvn -pl application -Dtest=GenericRestAdapterTest test`

Observed failing output:

```text
[ERROR] Tests run: 5, Failures: 0, Errors: 5, Skipped: 0
[ERROR] com.cl2.integration.adapter.out.generic.GenericRestAdapterTest.shouldGetCustomersWithWatermarkSubstitutionLimitAndJsonPathExtraction -- Time elapsed: 0.035 s <<< ERROR!
java.lang.IllegalStateException: Production adapter class is absent: com.cl2.integration.adapter.out.generic.GenericRestAdapter
Caused by: java.lang.ClassNotFoundException: com.cl2.integration.adapter.out.generic.GenericRestAdapter
```

Concerns:

- The focused test run now compiles and starts the Spring-backed harness successfully; the remaining failure is specifically the absence of the production `GenericRestAdapter` class, which is the intended Task 1 signal before implementation.
- The test context supplies neutral infrastructure beans and a mocked `OAuth2TokenCacheManager`, but it no longer encodes or enumerates any `GenericRestAdapter` constructor dependency list.
- The Maven run emitted existing Mockito/JDK dynamic-agent warnings that are unrelated to this task’s contract signal.
