# Deterministic Kafka API E2E

This Maven module exercises the integration-profile API against fresh MySQL 8.4 and Apache Kafka Testcontainers. It sends requests directly to the Spring application on its random HTTP port and supplies the tenant in `X-Tenant-ID`.

It does **not** start the Gateway, contact Keycloak, use an access token, or validate a JWT. Gateway and Keycloak QA coverage belongs to Task 7.

## Run the suite

From the repository root, first validate the Compose file (this only renders configuration; it does not start containers):

```bash
docker compose config --quiet
```

Run all E2E-module tests and required upstream modules:

```bash
mvn -pl e2e -am test
```

Run only the end-to-end tenant and Kafka scenario:

```bash
mvn -pl e2e -am -Dtest=IntegrationProfileE2ETest test
```

The targeted test starts MySQL 8.4 and `apache/kafka:3.8.1` through Testcontainers, then starts the application on a random HTTP port. It verifies create, read, update, deactivate, tenant isolation, and the created/updated/deactivated Kafka events. The observer filters every record by `eventType`, `profileId`, and `tenantId` with a bounded timeout; it does not use fixed sleeps.

## Initialization and topic

The event topic is exactly:

```text
integration-profile.events
```

Each E2E run uses a fresh Testcontainers MySQL instance. Application startup runs Flyway migrations before Hibernate validates the schema. The Testcontainers Kafka broker is started for the suite, and publishing/observing uses `integration-profile.events`; the broker permits topic auto-creation. The E2E profile also enables Kafka Admin fail-fast behavior so unavailable Kafka fails startup instead of silently skipping the event path.

## Optional local Compose stack

Compose is useful for manual API and infrastructure checks, but it is not the E2E test runtime. Its services use these host ports:

| Service | Host endpoint |
| --- | --- |
| MySQL | `localhost:3306` (or `MYSQL_PORT`) |
| Redis | `localhost:6379` (or `REDIS_PORT`) |
| Kafka | `localhost:29092` (or `KAFKA_PORT`) |
| Gateway | `http://localhost:8081` (or `GATEWAY_PORT`) |
| Application | no host port; `app:8080` only inside the Compose network |

Start the application dependencies and stack:

```bash
docker compose up -d mysql redis kafka
docker compose up -d --build app middleware
```

Compose health checks cover MySQL, Redis, and Kafka internally. The application check calls `http://localhost:8080/api/v1/integration-profiles?activeOnly=true` inside its container with `X-Tenant-ID`; the Gateway check is `http://localhost:8081/actuator/health`.

Compose Kafka exposes `29092` and has topic auto-creation enabled, so local application publishing uses the same `integration-profile.events` topic. Flyway initializes an empty Compose MySQL volume on application startup; it retains migration history until the volume is removed.

## Docker troubleshooting and cleanup

Testcontainers requires a reachable Docker daemon. Check access before running the E2E suite:

```bash
docker info
docker compose config --quiet
```

If Docker is unavailable, start or authorize the Docker Desktop/Engine service for the current user, then rerun the targeted Maven command. Typical Windows failures refer to `//./pipe/docker_engine` or denied access to the Docker client configuration; those are host-environment issues, not an E2E assertion pass.

To stop only this repository's Compose project and discard its MySQL, Redis, and Kafka volumes, run from this repository root:

```bash
docker compose down -v --remove-orphans
```

Then prove clean initialization by rerunning:

```bash
mvn -pl e2e -am -Dtest=IntegrationProfileE2ETest test
```

Testcontainers normally removes its own containers after the JVM exits. Do not use broad Docker prune commands for this workflow.
