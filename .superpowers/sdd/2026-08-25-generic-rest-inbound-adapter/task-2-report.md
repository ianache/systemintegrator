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
