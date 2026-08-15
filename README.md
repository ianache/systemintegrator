# Integration Profile Platform

Spring Boot service for tenant-isolated integration profiles and the SIGO Vehicle MVP. The tenant is supplied on every request through `X-Tenant-ID`; request payloads must not contain a tenant ID.

## Local run

Copy the non-sensitive local defaults, validate the rendered configuration, and start the complete stack:

```bash
cp .env.example .env
docker compose config --quiet
docker compose up --build -d mysql redis kafka app middleware
```

Compose starts MySQL, Redis, Kafka, the integration application, and the Gateway middleware. Only the Gateway publishes a host port (`http://localhost:8081`); it routes `/api/**` internally to `app:8080`. The application port remains on the internal Compose network.

The non-sensitive local override variables are:

| Variable | Default |
| --- | --- |
| `MYSQL_DATABASE` | `integration` |
| `MYSQL_PORT` | `3306` |
| `REDIS_PORT` | `6379` |
| `KAFKA_PORT` | `29092` |
| `GATEWAY_PORT` | `8081` |

`KEYCLOAK_ISSUER_URI` is optional and is intentionally not set in `.env.example`. Supply it only from an authorized local environment when you need to exercise the `qa-e2e` JWT validation profile; never commit issuer credentials or tokens.

For a non-Compose local application run, start dependencies and then run the app with the existing local defaults:

```bash
docker compose up -d mysql redis kafka
mvn spring-boot:run
```

Flyway runs on startup. It applies `V1__create_integration_profile.sql` to an empty database and Hibernate then validates the resulting schema; existing databases retain Flyway migration history.

## API examples

Create a profile:

```bash
TENANT_ID=71923e5e-a4cb-4956-91fd-a492fcab5715
curl -i -X POST http://localhost:8081/api/v1/integration-profiles \
  -H "X-Tenant-ID: $TENANT_ID" \
  -H 'Content-Type: application/json' \
  --data '{"businessDomain":"orders","externalSource":"erp","syncDirection":"INBOUND","sourceOfTruth":"PLATFORM"}'
```

List active profiles:

```bash
curl -H "X-Tenant-ID: $TENANT_ID" \
  http://localhost:8081/api/v1/integration-profiles
```

Include deactivated profiles:

```bash
curl -H "X-Tenant-ID: $TENANT_ID" \
  'http://localhost:8081/api/v1/integration-profiles?activeOnly=false'
```

Create and list canonical SIGO vehicles:

```bash
curl -i -X POST http://localhost:8081/api/v1/vehicles \
  -H "X-Tenant-ID: $TENANT_ID" -H 'Content-Type: application/json' \
  --data '{"vin":"VIN-001","brandCode":"TOYOTA","modelCode":"COROLLA","modelYear":2025}'
curl -H "X-Tenant-ID: $TENANT_ID" http://localhost:8081/api/v1/vehicles
```

Vehicle creation writes the canonical `vehicle.created` event to the MySQL outbox in the same transaction. Kafka publication and the SIGO HTTP adapter are separate integration components; the Inbox prevents duplicate event acceptance.

Run the suite, including the MySQL Testcontainers end-to-end verification:

```bash
mvn test
```

Docker must be available to run the MySQL/Kafka Testcontainers end-to-end tests. This environment does not have Docker locally, so those tests are pending; unit, MVC and compile checks remain runnable without containers. A future gateway/JWT layer will supply the authenticated tenant UUID; until then, local callers provide `X-Tenant-ID` directly.
