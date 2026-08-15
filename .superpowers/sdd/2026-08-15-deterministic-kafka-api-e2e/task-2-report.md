# Task 2 report — Create E2E Maven module and container bootstrap

## Files

- Modified `pom.xml` to make the reactor root POM-packaged and expose `application` and `e2e` modules.
- Created `application/pom.xml`, which retains the `com.cl2:integration:0.0.1-SNAPSHOT` application artifact while compiling the existing root `src/` tree. This was required because Maven rejects a JAR-packaged aggregator.
- Created `e2e/pom.xml` with the application artifact, Spring Boot Test, and Testcontainers JUnit/MySQL/Kafka dependencies.
- Created `e2e/src/test/java/com/cl2/integration/e2e/E2eApplicationTest.java`.
- Created `e2e/src/test/resources/application-e2e.yml`.

## Bootstrap configuration

- The E2E test activates the `e2e` profile and starts `IntegrationApplication` on a random HTTP port.
- Testcontainers uses `mysql:8.4` and `apache/kafka:3.8.1`; the latter is the image used by the local Compose configuration and is accepted by the installed Testcontainers Kafka container.
- Dynamic properties configure the MySQL JDBC URL, credentials, Kafka bootstrap servers, and `integration-profile.events` topic.
- The bootstrap assertion calls the tenant-scoped integration-profile list endpoint and expects HTTP 200, proving that the random HTTP port is reachable.

## Commands and results

1. `mvn -pl e2e -am -Dtest=E2eApplicationTest test`
   - Initial result: blocked by sandbox Maven Central socket permissions while resolving the Spring Boot parent POM.
2. `mvn -pl e2e -am -Dtest=E2eApplicationTest test` with Maven Central access
   - RED result: `Could not find the selected project in the reactor: e2e`, as expected before the root module declaration.
3. `mvn -pl e2e -am -Dtest=E2eApplicationTest -Dsurefire.failIfNoSpecifiedTests=false test` with Maven Central access
   - Result: Maven rejected the intermediate JAR-packaged aggregator with `packaging with value 'jar' is invalid. Aggregator projects require 'pom' as packaging`.
   - Resolution implemented: a POM-packaged root, retained application artifact in `application/pom.xml`, and E2E module dependency on that artifact.

## Concerns

- No post-restructure focused test was run because the user requested finalization without additional long commands. The current Testcontainers/Docker execution status is therefore unverified.
- Docker daemon availability was not reached by the executed commands, so no daemon error was observed.

## Fix round 1

### Changes

- `IntegrationProfileEventPublisher` now receives `integration-profile.events` through Spring property injection, with the existing topic as the fallback value. The hard-coded topic constant was removed.
- `IntegrationProfileEventPublisherTest` now verifies publication to a non-default configured topic.
- `application/pom.xml` configures Surefire to tolerate the E2E selector not matching application-module tests, allowing the exact reactor command to reach `e2e`.

### Commands and results

1. `mvn -pl application -Dtest=IntegrationProfileEventPublisherTest test`
   - RED result before the publisher fix: test compilation failed because the production constructor accepted only two arguments.
2. `mvn -pl application -Dtest=IntegrationProfileEventPublisherTest test`
   - GREEN result after the publisher fix: 2 tests run, 0 failures, 0 errors; build success.
3. `mvn -pl e2e -am -Dtest=E2eApplicationTest test`
   - First post-restructure result: Maven topology was valid, but the application module stopped on Surefire's `No tests matching pattern "E2eApplicationTest"` error.
   - After the module-level Surefire fix: reactor parent and application modules succeeded; `E2eApplicationTest` ran and failed before container startup with the exact Testcontainers error: `Could not find a valid Docker environment`. The configured `NpipeSocketClientProviderStrategy` received `BadRequestException (Status 400)` from `npipe://\\.\\pipe\\docker_cli`, with no valid Docker environment available.

### Concerns

- The bootstrap test is blocked only by unavailable/invalid local Docker daemon access; Maven topology, compilation and test discovery now reach the E2E test.
- The application module still compiles the existing root `src/` tree through `application/pom.xml`. Relocating sources into the module would be a broader build-layout change and is deferred from Task 2.
