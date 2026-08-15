# Integration Profile Platform

Spring Boot service for tenant-isolated integration profiles and the SIGO Vehicle MVP. The tenant is supplied on every request through `X-Tenant-ID`; request payloads must not contain a tenant ID.

## Local run

Start MySQL 8 using the repository Compose configuration, then start the application:

```bash
docker compose up -d
mvn spring-boot:run
```

The application uses these environment variables (with local defaults):

| Variable | Default |
| --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/integration?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` |
| `DB_USERNAME` | `integration` |
| `DB_PASSWORD` | `integration` |

For example:

```bash
DB_URL='jdbc:mysql://localhost:3306/integration?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' \
DB_USERNAME=integration DB_PASSWORD=integration mvn spring-boot:run
```

Flyway runs on startup. It applies `V1__create_integration_profile.sql` to an empty database and Hibernate then validates the resulting schema; existing databases retain Flyway migration history.

## API examples

Create a profile:

```bash
TENANT_ID=71923e5e-a4cb-4956-91fd-a492fcab5715
curl -i -X POST http://localhost:8080/api/v1/integration-profiles \
  -H "X-Tenant-ID: $TENANT_ID" \
  -H 'Content-Type: application/json' \
  --data '{"businessDomain":"orders","externalSource":"erp","syncDirection":"INBOUND","sourceOfTruth":"PLATFORM"}'
```

List active profiles:

```bash
curl -H "X-Tenant-ID: $TENANT_ID" \
  http://localhost:8080/api/v1/integration-profiles
```

Include deactivated profiles:

```bash
curl -H "X-Tenant-ID: $TENANT_ID" \
  'http://localhost:8080/api/v1/integration-profiles?activeOnly=false'
```

Create and list canonical SIGO vehicles:

```bash
curl -i -X POST http://localhost:8080/api/v1/vehicles \
  -H "X-Tenant-ID: $TENANT_ID" -H 'Content-Type: application/json' \
  --data '{"vin":"VIN-001","brandCode":"TOYOTA","modelCode":"COROLLA","modelYear":2025}'
curl -H "X-Tenant-ID: $TENANT_ID" http://localhost:8080/api/v1/vehicles
```

Vehicle creation writes the canonical `vehicle.created` event to the MySQL outbox in the same transaction. Kafka publication and the SIGO HTTP adapter are separate integration components; the Inbox prevents duplicate event acceptance.

Run the suite, including the MySQL Testcontainers end-to-end verification:

```bash
mvn test
```

Docker must be available to run the MySQL/Kafka Testcontainers end-to-end tests. This environment does not have Docker locally, so those tests are pending; unit, MVC and compile checks remain runnable without containers. A future gateway/JWT layer will supply the authenticated tenant UUID; until then, local callers provide `X-Tenant-ID` directly.
