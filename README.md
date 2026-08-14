# Integration Profile Platform

Spring Boot service for tenant-isolated integration profiles. The tenant is supplied on every request through `X-Tenant-ID`; request payloads must not contain a tenant ID.

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

Run the suite, including the MySQL Testcontainers end-to-end verification:

```bash
mvn test
```

Docker must be available to run the container-backed tests. A future gateway/JWT layer will supply the authenticated tenant UUID; until then, local callers provide `X-Tenant-ID` directly.
