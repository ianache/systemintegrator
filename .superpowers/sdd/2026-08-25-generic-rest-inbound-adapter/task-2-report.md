Task 2 Report: Generic REST inbound request adapter

Summary
- Added `GenericRestAdapter` as a Spring component under `application/src/main/java/com/cl2/integration/adapter/out/generic/`.
- Implemented GET request construction from `IntegrationProfile` and `ExtractionConfig`.
- Validated profile endpoints as absolute `http` or `https` URLs.
- Resolved `config.path` relative to the profile endpoint.
- Copied configured query parameters into the request and replaced exact `:lastSyncWithBuffer` placeholder values with the provided watermark timestamp in UTC ISO-8601 form.
- Copied configured headers into a fresh `HttpHeaders` instance without mutating configuration state.
- Applied Basic, Bearer, API Key, and OAuth2 Client Credentials authentication.
- Used `OAuth2TokenCacheManager.getAccessToken(String tenantId, String tokenUrl, String clientId, String clientSecret, String scope)` for OAuth2 token resolution.
- Ensured generated `Authorization` values win over configured headers by applying authentication after header copies.
- Added sanitized debug logging using `SensitiveDataRedactor` so credentials do not appear in logs.
- Implemented the smallest JSON response extraction needed by the current contract tests using the configured JSONPath.

Files changed
- Created `application/src/main/java/com/cl2/integration/adapter/out/generic/GenericRestAdapter.java`
- Created `.superpowers/sdd/2026-08-25-generic-rest-inbound-adapter/task-2-report.md`

Verification
- Ran `mvn -pl application -Dtest=GenericRestAdapterTest test`
- Result: BUILD SUCCESS
- Test summary: 5 tests run, 0 failures, 0 errors, 0 skipped

Notes
- `ExtractionConfig` did not require changes for REST support in this task.
- Verification required network-enabled Maven dependency resolution because the sandboxed red run could not reach Maven Central.
- Existing compiler and test warnings unrelated to this task remain in the module output.

Fix Round 1

Summary
- Added focused regression tests for configured `POST` requests, unsupported `watermarkFormat`, and endpoint userinfo rejection.
- Updated `GenericRestAdapter` to honor supported configured HTTP methods, explicitly reject unsupported methods including `DELETE`, `TRACE`, and `CONNECT`, validate and apply `ISO_8601` watermark formatting, and reject endpoints containing URI userinfo before request construction or logging.

Files changed
- Modified `application/src/test/java/com/cl2/integration/adapter/out/generic/GenericRestAdapterTest.java`
- Modified `application/src/main/java/com/cl2/integration/adapter/out/generic/GenericRestAdapter.java`

Verification Commands and Output

Command
```text
mvn -pl application -Dtest=GenericRestAdapterTest test
```

Sandboxed red run output
```text
Acceso denegado.
[ERROR] Non-resolvable parent POM for com.cl2:integration-parent:0.0.1-SNAPSHOT: Could not transfer artifact org.springframework.boot:spring-boot-starter-parent:pom:3.4.5 from/to central (https://repo.maven.apache.org/maven2): Permission denied: getsockopt
```

Focused red run output with network-enabled Maven
```text
[INFO] Running com.cl2.integration.adapter.out.generic.GenericRestAdapterTest
[ERROR] Tests run: 8, Failures: 2, Errors: 1, Skipped: 0, Time elapsed: 3.840 s <<< FAILURE! -- in com.cl2.integration.adapter.out.generic.GenericRestAdapterTest
[ERROR] com.cl2.integration.adapter.out.generic.GenericRestAdapterTest.shouldHonorPostExtractionMethod ... 404 Not Found ... POST | GET <<<<< HTTP method does not match
[ERROR] com.cl2.integration.adapter.out.generic.GenericRestAdapterTest.shouldRejectUnsupportedWatermarkFormatsExplicitly ... Expecting actual throwable to be an instance of: java.lang.IllegalArgumentException but was: org.springframework.web.client.HttpClientErrorException$NotFound
[ERROR] com.cl2.integration.adapter.out.generic.GenericRestAdapterTest.shouldRejectEndpointsContainingUserinfo ... Expecting actual throwable to be an instance of: java.lang.IllegalArgumentException but was: org.springframework.web.client.HttpClientErrorException$NotFound
[INFO] BUILD FAILURE
```

Green run output after production fix
```text
[INFO] Running com.cl2.integration.adapter.out.generic.GenericRestAdapterTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.666 s -- in com.cl2.integration.adapter.out.generic.GenericRestAdapterTest
[INFO] Results:
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time: 17.391 s
[INFO] Finished at: 2026-08-25T23:28:51-05:00
```

Notes
- The focused red run failures matched the reviewer findings: method dispatch stayed on `GET`, unsupported watermark formats were not validated before the HTTP call, and endpoints containing userinfo were accepted through request construction.
