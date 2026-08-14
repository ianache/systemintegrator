# Task 7 Report: Clean Integration Verification

## Changed files

- `src/test/resources/application-test.yml`: test-profile JPA/Flyway configuration with the MySQL JDBC driver.
- `src/test/java/com/cl2/integration/IntegrationApplicationTest.java`: shared Testcontainers MySQL 8.4 fixture that dynamically supplies Spring's JDBC URL, username, and password.
- `src/test/java/com/cl2/integration/IntegrationProfileEndToEndTest.java`: real HTTP E2E test for two-tenant creation/list isolation, cross-tenant GET/PUT/DELETE `404` responses, and retained inactive profile state after deletion.
- `README.md`: Docker/Maven local commands, database environment variables, Flyway behaviour, API examples, and future gateway/JWT tenant ownership note.

## Commit

- `cc2a9dc test: verify integration profile slice`

## Test commands and output

| Command | Result |
| --- | --- |
| `mvn -Dtest=IntegrationProfileEndToEndTest test` before the fixture | Expected red: Spring/Flyway attempted `localhost:3306` and failed with MySQL communications-link failure. |
| `mvn -Dtest=IntegrationProfileEndToEndTest test` after the fixture | Blocked: Testcontainers initialized, then reported `Could not find a valid Docker environment`. |
| `mvn -Dtest=IntegrationProfileControllerTest,IntegrationProfileJpaEntityTest,SpringDataIntegrationProfileRepositoryTest,IntegrationProfileServiceTest,IntegrationProfileTest,TenantContextTest test` | Pass: 38 tests, 0 failures, 0 errors. |
| `mvn test` | Blocked: Testcontainers persistence/E2E tests cannot start without Docker; the pre-existing `TenantFilterTest` separately attempts `localhost:3306` and also fails without a local MySQL instance. |
| `mvn -DskipTests test` | Pass: production and final test sources compile. |
| `git diff --check` | Pass: no whitespace errors. |

## Self-review

- Testcontainers uses MySQL 8.4 and overrides all Spring datasource connection properties, so the Task 7 E2E test does not use developer-local credentials or URL.
- The acceptance test uses `TestRestTemplate` against a random-port embedded server rather than mocks.
- Each cross-tenant resource mutation/read is asserted as `404`; the owning tenant can still delete its profile and retrieve it with `activeOnly=false` as inactive.
- Unit and web tests remain separate from the container-backed fixture.
- README covers required commands, variables, migration operation, API calls, and future tenant source.

## Environment blockers

- Docker is not installed or available on `PATH`; Testcontainers 1.20.6 cannot discover a valid Docker environment. This prevents MySQL image startup, Flyway migration execution, persistence integration testing, and Task 7 E2E execution.
- Maven dependency resolution initially required sandbox network approval; it succeeded once Maven was allowed to access Central.

## Concerns

- Docker is not available in the environment, so MySQL Testcontainers verification remains blocked.

## Fix round 1

### Commit

- `113c3ab test: isolate tenant filter verification`

### Changes

- Replaced `TenantFilterTest`'s `@SpringBootTest` full application context with a `@WebMvcTest` MVC slice and explicitly imported only its nested boundary controller. The test still verifies both direct filter behavior and registered filter behavior, but no longer initializes JPA, Flyway, or a developer-local datasource.
- Extended the E2E isolation test to fetch the owner profile after cross-tenant `PUT` and `DELETE` return `404`, then assert its identity fields remain unchanged and `active` remains `true`.
- Corrected the Compose finding: `compose.yaml` is present at the worktree root. The previous report statement claiming that no Compose file existed was false.

### Verification

| Command | Result |
| --- | --- |
| `mvn -Dtest=TenantFilterTest test` before the MVC-slice change | Expected red: `@SpringBootTest` initialized JPA/Flyway and failed connecting to `localhost:3306`. |
| `mvn -Dtest=TenantFilterTest test` after the MVC-slice change | Pass: 7 tests, 0 failures, 0 errors; no JPA/Flyway datasource startup. |
| `mvn -Dtest=IntegrationProfileControllerTest,IntegrationProfileJpaEntityTest,SpringDataIntegrationProfileRepositoryTest,IntegrationProfileServiceTest,IntegrationProfileTest,TenantContextTest,TenantFilterTest test` | Pass: 45 tests, 0 failures, 0 errors. |
| `mvn test` | Blocked only by Docker discovery: `IntegrationProfilePersistenceAdapterTest` and `IntegrationProfileEndToEndTest` cannot start MySQL Testcontainers. TenantFilterTest passes in the same full-suite run. |
| `git diff --check` | Pass: no whitespace errors before the fix commit. |
