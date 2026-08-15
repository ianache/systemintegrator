# Integration Profile Platform

Spring Boot service for tenant-isolated integration profiles and the SIGO Vehicle MVP. The application receives the tenant through `X-Tenant-ID`; Gateway requests derive that header from the validated JWT `tenant_id` claim, while direct application calls provide it explicitly. Request payloads must not contain a tenant ID.

## Local run

Copy the non-sensitive local defaults, validate the rendered configuration, and start the complete stack:

```bash
cp .env.example .env
docker compose config --quiet
docker compose up --build -d mysql redis kafka app middleware
```

Kafka verification status: Compose now uses the available official `apache/kafka:3.8.1`
image. Kafka-only startup passed after the tag correction; full-stack startup still
requires a Docker engine with accessible API permissions.

Compose starts MySQL, Redis, Kafka, the integration application, and the Gateway middleware. Only the Gateway publishes a host port (`http://localhost:8081`); it routes `/api/**` internally to `app:8080`. The application port remains on the internal Compose network.

The Gateway starts with the safe `local` profile by default. This profile does not activate the Keycloak resource-server configuration and does not contact an external issuer. The `qa-e2e` profile activates JWT validation with `KEYCLOAK_ISSUER_URI`; it accepts a Bearer token, reads its `tenant_id` claim, and replaces any caller-supplied `X-Tenant-ID` before proxying the request. Callers must not send or trust `X-Tenant-ID` through the Gateway.

The non-sensitive local override variables are:

| Variable | Default |
| --- | --- |
| `MYSQL_DATABASE` | `integration` |
| `MYSQL_PORT` | `3306` |
| `REDIS_PORT` | `6379` |
| `KAFKA_PORT` | `29092` |
| `GATEWAY_PORT` | `8081` |

`KEYCLOAK_ISSUER_URI` is optional and is intentionally not set in `.env.example`. To run the Gateway against the authorized QA issuer, explicitly activate `qa-e2e` and supply both variables for that command; never commit issuer credentials or tokens:

```bash
SPRING_PROFILES_ACTIVE=qa-e2e \
KEYCLOAK_ISSUER_URI=https://oauth2.qa.comsatel.com.pe/realms/microservicios \
docker compose up -d --build mysql redis kafka app middleware
```

PowerShell equivalent:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'qa-e2e'
$env:KEYCLOAK_ISSUER_URI = 'https://oauth2.qa.comsatel.com.pe/realms/microservicios'
docker compose up -d --build mysql redis kafka app middleware
```

For a non-Compose local application run, start dependencies and then run the app with the existing local defaults:

```bash
docker compose up -d mysql redis kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 mvn spring-boot:run
```

The `KAFKA_BOOTSTRAP_SERVERS` override is required for an application running on the host: Compose publishes Kafka on `localhost:29092`. The Compose `app` service runs inside the integration network and therefore uses `kafka:9092` instead. PowerShell equivalent:

```powershell
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:29092'
mvn spring-boot:run
```

Flyway runs on startup. It applies `V1__create_integration_profile.sql` to an empty database and Hibernate then validates the resulting schema; existing databases retain Flyway migration history.

## Gateway API examples (QA E2E)

With `qa-e2e` active, use an access token whose `tenant_id` claim is a UUID. The Gateway derives the tenant from that claim; do not add `X-Tenant-ID` to Gateway requests.

Create a profile:

```bash
ACCESS_TOKEN='<qa-access-token>'
curl -i -X POST http://localhost:8081/api/v1/integration-profiles \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"businessDomain":"orders","externalSource":"erp","syncDirection":"INBOUND","sourceOfTruth":"PLATFORM"}'
```

List active profiles:

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:8081/api/v1/integration-profiles
```

Include deactivated profiles:

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  'http://localhost:8081/api/v1/integration-profiles?activeOnly=false'
```

Create and list canonical SIGO vehicles:

```bash
curl -i -X POST http://localhost:8081/api/v1/vehicles \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  --data '{"vin":"VIN-001","brandCode":"TOYOTA","modelCode":"COROLLA","modelYear":2025}'
curl -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8081/api/v1/vehicles
```

Vehicle creation writes the canonical `vehicle.created` event to the MySQL outbox in the same transaction. Kafka publication and the SIGO HTTP adapter are separate integration components; the Inbox prevents duplicate event acceptance.

Run the suite, including the MySQL Testcontainers end-to-end verification:

```bash
mvn test
```

## Deterministic API/Kafka E2E

The API/Kafka E2E module uses fresh MySQL 8.4 and Kafka Testcontainers, sends requests directly to the application with `X-Tenant-ID`, and verifies events on `integration-profile.events`. It does not contact Keycloak or the Gateway; Gateway QA is Task 7. See [the E2E runbook](e2e/README.md) for the exact module, targeted-test, Compose validation, Docker troubleshooting, initialization, health, and cleanup commands.

Docker must be available to run the MySQL/Kafka Testcontainers suite. For a direct, non-Gateway application run only, the application validates a caller-provided `X-Tenant-ID`; that header is not a Gateway client contract.
