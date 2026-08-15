# Task 3 Report: Compose application and middleware services

## Files changed

- `Dockerfile` — multi-stage Java 21 Maven build and JRE runtime image for the root Spring Boot application; installs `curl` for the Compose healthcheck.
- `gateway/Dockerfile` — equivalent Java 21 image for the independent Gateway module.
- `compose.yaml` — adds `app` and `middleware` while retaining MySQL, Redis, Kafka, their healthchecks, volumes, and the internal network.
- `.env.example` — documents only non-sensitive local port/database defaults and leaves the optional issuer commented out.
- `README.md` — documents configuration validation, complete-stack startup, service routing, and the Gateway-facing API endpoint.

## Commands and results

| Command | Result |
| --- | --- |
| Compose smoke before implementation (rendered service-name check) | Expected failure: `app` and `middleware` were missing. |
| `docker compose config --quiet` | Passed after implementation. Docker emitted non-fatal warnings that its user config file was inaccessible in the sandbox. |
| Rendered Compose service/variable/secret smoke check | Passed: `mysql`, `redis`, `kafka`, `app`, and `middleware` render; no unresolved Compose variables; no tracked environment files other than `.env.example`. |
| `mvn -f gateway/pom.xml test` | Passed after sandbox network approval: 7 tests run, 0 failures, 0 errors, 0 skipped. The initial sandbox-only attempt could not fetch Maven Central dependencies (`Permission denied: getsockopt`). |
| `docker compose build app middleware` | Not completed. The sandboxed attempt failed before building because Docker could not evaluate the build-context path (`Access is denied`). An approved rerun started, then was explicitly interrupted by the user. Per instruction, it was not retried. |
| `git diff --check` | Passed before this report was written and is rerun immediately before commit. |

## Self-review

- The root app exposes only its internal port `8080`, depends on healthy MySQL, Redis, and Kafka, and receives datasource, Redis, and Kafka connection settings.
- The middleware publishes `${GATEWAY_PORT:-8081}`, depends on a healthy app, routes to `http://app:8080`, and receives an optional `KEYCLOAK_ISSUER_URI` only.
- Existing MySQL, Redis, Kafka services, healthchecks, volumes, and the `integration-internal` network are preserved.
- No Keycloak credentials or tokens were added. The issuer example is commented out and contains no secret.
- README examples consistently use the public Gateway port for API calls.

## Concerns

- The Docker images were not fully built in this environment because the approved build was interrupted. Compose configuration and Gateway tests are verified, but image build/runtime verification remains outstanding.
- The Docker CLI emitted non-fatal warnings about inaccessible user-level Docker configuration while rendering Compose; `docker compose config --quiet` still exited successfully.

## Round 1 review fixes

- `compose.yaml` now sets the Gateway's `SPRING_PROFILES_ACTIVE` to `${SPRING_PROFILES_ACTIVE:-local}`. The default local Compose configuration does not activate `qa-e2e` or require an external Keycloak issuer; QA E2E explicitly overrides the profile and provides `KEYCLOAK_ISSUER_URI`.
- The application healthcheck now uses `curl -fsS`, so an HTTP error response makes the healthcheck fail.
- `README.md` now distinguishes the local and `qa-e2e` Gateway profiles, supplies exact Bash and PowerShell QA E2E environment commands, and uses Bearer-token examples. The Gateway derives `X-Tenant-ID` from the validated JWT `tenant_id` claim and replaces any caller-provided header; direct application-only header behavior is explicitly labeled.

### Commands and results

| Command | Result |
| --- | --- |
| `docker compose config --quiet` | Passed. Docker emitted only the known non-fatal warning that its user config file is inaccessible in this sandbox. |
| `docker compose config` (filtered rendered values) | Passed: the app healthcheck renders `curl -fsS`; middleware renders `SPRING_PROFILES_ACTIVE: local` and an empty issuer by default, confirming no external QA dependency. |
| `mvn -f gateway/pom.xml test` | Passed after the sandbox-only Maven Central attempt was retried with network approval: 7 tests run, 0 failures, 0 errors, 0 skipped. |
| `git diff --check` | Passed; Git emitted only line-ending conversion warnings for the edited tracked text files. |
