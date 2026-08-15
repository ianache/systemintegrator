# Task 2 Report: JWT security and tenant propagation

## Files changed

- `gateway/src/main/java/com/cl2/integration/gateway/security/GatewaySecurityConfig.java` — configures the reactive JWT resource-server chain, permits only health, and requires authentication for every other request.
- `gateway/src/main/java/com/cl2/integration/gateway/security/TenantClaimGatewayFilter.java` — obtains the authenticated JWT from the reactive security context, rejects absent or malformed `tenant_id` claims with 403, and replaces every incoming `X-Tenant-ID` with the trusted UUID.
- `gateway/src/main/java/com/cl2/integration/gateway/security/InvalidTenantClaimException.java` — local signal for a missing or invalid tenant claim.
- `gateway/src/main/resources/application.yml` — maps `KEYCLOAK_ISSUER_URI` to `spring.security.oauth2.resourceserver.jwt.issuer-uri`.
- `gateway/src/test/java/com/cl2/integration/gateway/security/GatewaySecurityTest.java` — offline WebTestClient coverage for missing and invalid bearer tokens (401), missing/malformed tenant claims (403), using `mockJwt()` and a local rejecting decoder.
- `gateway/src/test/java/com/cl2/integration/gateway/security/TenantClaimGatewayFilterTest.java` — verifies that duplicate malicious tenant headers are replaced with exactly the UUID claim before downstream invocation.

## TDD evidence

### RED

Command:

```powershell
mvn -f gateway/pom.xml test '-Dtest=GatewaySecurityTest,TenantClaimGatewayFilterTest'
```

Result: failed as expected during test compilation because `TenantClaimGatewayFilter` did not exist. The test fixture import issue encountered in the first attempted RED run was corrected before this clean RED result.

### GREEN

Command:

```powershell
mvn -f gateway/pom.xml test '-Dtest=GatewaySecurityTest,TenantClaimGatewayFilterTest'
```

Result: passed — 5 tests, 0 failures, 0 errors, 0 skipped. Tests use only a local rejecting decoder and `SecurityMockServerConfigurers.mockJwt()`; no Keycloak or QA request is made.

Additional regression command:

```powershell
mvn -f gateway/pom.xml test
```

Result: passed — 7 tests, 0 failures, 0 errors, 0 skipped.

## Self-review

- Verified the security chain returns 401 for both absent bearer tokens and decoder-rejected bearer tokens.
- Verified authenticated JWTs without a tenant claim or with a malformed tenant claim return 403.
- Verified the downstream exchange receives a single claim-derived `X-Tenant-ID` after client-supplied duplicate headers are removed.
- Verified the issuer property is environment-backed and the unchanged Task 1 context test starts without external Keycloak discovery.
- Ran `git diff --check`; no whitespace errors were reported.

## Concerns

- Spring Security 6.4 marks `OAuth2ResourceServerSpec.jwt()` deprecated for a future release; it is retained because it is the interface explicitly required for this task.
- Maven emitted existing JDK/Mockito dynamic-agent warnings during tests. They do not affect the passing offline test results.
