# Casos de prueba manuales E2E — Integration Platform

## 1. Información general

| Campo | Valor |
|---|---|
| Sistema | Integration Platform / SIGO Vehicle MVP |
| Entorno | Docker Compose local + Keycloak QA opcional |
| Rama objetivo | `main` |
| Gateway | `http://localhost:8081` |
| Tema de perfiles | `integration-profile.events` |
| Tema de outbox de vehículos | `integration.events` |
| Base de datos | MySQL 8.4 |
| Cache | Redis 7.4 |
| Mensajería | Apache Kafka 3.8.1 |
| Realm QA | `microservicios` |

### Reglas de ejecución

- Ejecutar los casos en orden cuando se indique una dependencia.
- Registrar resultado `PASS`, `FAIL` o `BLOCKED`, fecha, ejecutor y evidencia.
- No guardar contraseñas, access tokens ni headers `Authorization` en evidencias.
- Para llamadas directas a `app`, usar `X-Tenant-ID`.
- Para llamadas por `middleware` con perfil `qa-e2e`, no confiar en `X-Tenant-ID`: el Gateway lo reemplaza con el claim `tenant_id` del JWT.
- Usar dos UUID de tenant distintos:

```text
TENANT_A = 11111111-1111-1111-1111-111111111111
TENANT_B = 22222222-2222-2222-2222-222222222222
```

## 2. Preparación del entorno

### PRE-01 — Validar configuración Compose

```powershell
docker compose config --quiet
```

**Esperado:** código `0`, sin errores de variables o YAML.

### PRE-02 — Levantar todos los servicios

```powershell
docker compose up -d --build mysql redis kafka app middleware
docker compose ps
```

**Esperado:** `mysql`, `redis`, `kafka`, `app` y `middleware` en `healthy`.

### PRE-03 — Health de infraestructura

```powershell
docker compose exec mysql mysqladmin ping -uintegration -pintegration --silent
docker compose exec redis redis-cli ping
docker compose exec kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server kafka:9092
```

**Esperado:** MySQL responde `mysqld is alive`, Redis responde `PONG` y Kafka devuelve capacidades del broker.

### PRE-04 — Health de aplicación y Gateway

```powershell
docker compose exec app curl -fsS -H 'X-Tenant-ID: 11111111-1111-1111-1111-111111111111' `
  'http://localhost:8080/api/v1/integration-profiles?activeOnly=true'
curl.exe -fsS http://localhost:8081/actuator/health
```

**Esperado:** HTTP `200`; el Gateway devuelve `{"status":"UP"}`.

### PRE-05 — Inicialización Flyway

```powershell
docker compose exec mysql mysql -uintegration -pintegration integration `
  -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

**Esperado:** migraciones aplicadas con `success = 1`, incluyendo las tablas de perfiles, vehículos, outbox e inbox.

## 3. Seguridad y Gateway

Estos casos requieren iniciar el middleware con el perfil QA:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'qa-e2e'
$env:KEYCLOAK_ISSUER_URI = 'https://oauth2.qa.comsatel.com.pe/realms/microservicios'
docker compose up -d --force-recreate middleware
```

Obtener el token de forma interactiva. No imprimir ni guardar la respuesta completa:

```powershell
$env:KEYCLOAK_CLIENT_ID = 'admin-cli'
$env:KEYCLOAK_USERNAME = 'user'
$securePassword = Read-Host 'Keycloak password' -AsSecureString
$env:KEYCLOAK_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
$token = Invoke-RestMethod -Method Post `
  -Uri "$env:KEYCLOAK_ISSUER_URI/protocol/openid-connect/token" `
  -ContentType 'application/x-www-form-urlencoded' `
  -Body @{ grant_type='password'; client_id=$env:KEYCLOAK_CLIENT_ID; username=$env:KEYCLOAK_USERNAME; password=$env:KEYCLOAK_PASSWORD }
$env:ACCESS_TOKEN = $token.access_token
```

El usuario debe tener un claim `tenant_id` con formato UUID. Si no lo tiene, los casos autenticados deben marcarse `BLOCKED` por precondición de Keycloak, no como fallo del API.

### Diagnóstico `invalid_grant` / `Invalid user credentials`

`USUARIO/PASSWORD` no necesariamente es un usuario del realm `microservicios`. En Keycloak, una cuenta administrativa creada durante la instalación suele pertenecer al realm `master`; esa cuenta no puede solicitar tokens usando el endpoint de `microservicios`.

Verificar en la consola de Keycloak:

1. Cambiar explícitamente al realm `microservicios`.
2. Ir a `Users` y confirmar que existe el usuario que se usará para la prueba.
3. Confirmar que el usuario está habilitado, tiene contraseña definida y que la contraseña no está marcada como temporal.
4. Confirmar que el cliente indicado por `KEYCLOAK_CLIENT_ID` existe en `microservicios` y tiene `Direct Access Grants` habilitado.
5. Confirmar que el usuario tiene un mapper o atributo que emite `tenant_id` como UUID.

Si `user/welcome1` solo existe en `master`, usar un usuario QA creado en `microservicios` y asignarlo a `KEYCLOAK_USERNAME`; no cambiar el issuer del Gateway a `master`, porque el Gateway debe validar tokens cuyo issuer sea `microservicios`.

La cuenta administrativa de `master` puede servir para administrar el realm, pero no debe utilizarse directamente como usuario de negocio del E2E. No registrar contraseñas ni tokens en la evidencia.

### SEC-01 — Health público del Gateway

```powershell
curl.exe -i http://localhost:8081/actuator/health
```

**Esperado:** `200`.

### SEC-02 — Solicitud sin Bearer token

```powershell
curl.exe -i http://localhost:8081/api/v1/integration-profiles
```

**Esperado:** `401`; no se crea ni modifica información.

### SEC-03 — Bearer token inválido

```powershell
curl.exe -i http://localhost:8081/api/v1/integration-profiles -H 'Authorization: Bearer invalid-token'
```

**Esperado:** `401`.

### SEC-04 — Token con tenant válido

```powershell
curl.exe -i http://localhost:8081/api/v1/integration-profiles `
  -H "Authorization: Bearer $env:ACCESS_TOKEN"
```

**Esperado:** `200` y acceso asociado exclusivamente al `tenant_id` del token.

### SEC-05 — Header de tenant manipulado

Enviar un `X-Tenant-ID` distinto al claim del token.

```powershell
curl.exe -i http://localhost:8081/api/v1/integration-profiles `
  -H "Authorization: Bearer $env:ACCESS_TOKEN" `
  -H 'X-Tenant-ID: 22222222-2222-2222-2222-222222222222'
```

**Esperado:** el Gateway ignora el header y utiliza el `tenant_id` autenticado.

### SEC-06 — Token sin `tenant_id`

Usar un token de prueba autorizado sin el claim.

**Esperado:** `403`; no se reenvía la solicitud a `app`.

### SEC-07 — `tenant_id` malformado

Usar un token cuyo claim `tenant_id` no sea UUID.

**Esperado:** `403`.

### SEC-08 — Issuer incorrecto o token expirado

Usar un token expirado o emitido por otro issuer.

**Esperado:** `401`.

## 4. API de Integration Profiles — tenant A

Para pruebas directas reemplazar `BASE_URL` por `http://localhost:8080` y enviar `X-Tenant-ID: $TENANT_A`. Para pruebas end-to-end usar `http://localhost:8081` y Bearer token.

### IP-01 — Crear perfil válido

```powershell
curl.exe -i -X POST http://localhost:8081/api/v1/integration-profiles `
  -H "Authorization: Bearer $env:ACCESS_TOKEN" `
  -H 'Content-Type: application/json' `
  --data '{"businessDomain":"orders","externalSource":"erp","syncDirection":"INBOUND","sourceOfTruth":"PLATFORM"}'
```

**Esperado:** `201`; guardar `id` y `version` inicial `0` como `PROFILE_ID` y `PROFILE_VERSION`.

### IP-02 — Crear con campos obligatorios ausentes

Enviar `{}`, campos vacíos, `businessDomain` vacío o `externalSource` vacío.

**Esperado:** `400`, `errorCode = VALIDATION_FAILED`.

### IP-03 — Crear con enum inválido

Enviar valores no soportados para `syncDirection` o `sourceOfTruth`.

**Esperado:** `400`, `errorCode = VALIDATION_FAILED`.

### IP-04 — Listar perfiles activos

```powershell
curl.exe -i http://localhost:8081/api/v1/integration-profiles `
  -H "Authorization: Bearer $env:ACCESS_TOKEN"
```

**Esperado:** `200`; contiene solo perfiles activos del tenant autenticado.

### IP-05 — Obtener perfil por ID

```powershell
curl.exe -i "http://localhost:8081/api/v1/integration-profiles/$PROFILE_ID" `
  -H "Authorization: Bearer $env:ACCESS_TOKEN"
```

**Esperado:** `200`; `tenantId`, `id` y datos coinciden con la creación.

### IP-06 — Obtener ID inexistente

**Esperado:** `404`, `errorCode = INTEGRATION_PROFILE_NOT_FOUND`.

### IP-07 — UUID de ruta inválido

```powershell
curl.exe -i http://localhost:8081/api/v1/integration-profiles/not-a-uuid `
  -H "Authorization: Bearer $env:ACCESS_TOKEN"
```

**Esperado:** `400`; no hay acceso a datos de otro tenant.

### IP-08 — Actualizar con versión correcta

```powershell
curl.exe -i -X PUT "http://localhost:8081/api/v1/integration-profiles/$PROFILE_ID" `
  -H "Authorization: Bearer $env:ACCESS_TOKEN" -H 'Content-Type: application/json' `
  --data '{"businessDomain":"orders-updated","externalSource":"erp","syncDirection":"INBOUND","sourceOfTruth":"PLATFORM","expectedVersion":0}'
```

**Esperado:** `200`; `version` incrementa a `1`.

### IP-09 — Actualizar con versión obsoleta

Repetir IP-08 usando `expectedVersion: 0` después de que la versión sea `1`.

**Esperado:** `409`, `errorCode = INTEGRATION_PROFILE_CONFLICT`; los datos permanecen consistentes.

### IP-10 — Actualizar con payload inválido

Enviar campos vacíos, enums inválidos o `expectedVersion` ausente.

**Esperado:** `400`, `errorCode = VALIDATION_FAILED`.

### IP-11 — Desactivación lógica

```powershell
curl.exe -i -X DELETE "http://localhost:8081/api/v1/integration-profiles/$PROFILE_ID" `
  -H "Authorization: Bearer $env:ACCESS_TOKEN"
```

**Esperado:** `204`; la fila no se elimina físicamente.

### IP-12 — Listar activos después de desactivar

**Esperado:** `200`; el perfil no aparece con `activeOnly=true`.

### IP-13 — Listar histórico

```powershell
curl.exe -i 'http://localhost:8081/api/v1/integration-profiles?activeOnly=false' `
  -H "Authorization: Bearer $env:ACCESS_TOKEN"
```

**Esperado:** `200`; el perfil aparece con `active=false`.

### IP-14 — Desactivar perfil inexistente

**Esperado:** `404`, `errorCode = INTEGRATION_PROFILE_NOT_FOUND`.

## 5. Aislamiento multitenant

### TEN-01 — Tenant B no lista perfiles de tenant A

Usar un token con `tenant_id = TENANT_B` o llamada directa con `X-Tenant-ID: TENANT_B`.

**Esperado:** `200` con lista vacía, sin filtrar datos de A.

### TEN-02 — Tenant B no obtiene perfil de tenant A

```powershell
curl.exe -i "http://localhost:8081/api/v1/integration-profiles/$PROFILE_ID" `
  -H "Authorization: Bearer $TOKEN_TENANT_B"
```

**Esperado:** `404`, nunca `200`.

### TEN-03 — Tenant B no modifica perfil de tenant A

**Esperado:** `404` o `409` según el contrato; no se modifica la versión ni el contenido de A.

### TEN-04 — Tenant B no desactiva perfil de tenant A

**Esperado:** `404`; el perfil de A permanece activo/inactivo según su estado anterior.

### TEN-05 — Mismo VIN en tenants distintos

Crear el mismo VIN para A y B.

**Esperado:** permitido; la unicidad es `(tenant_id, vin)`.

### TEN-06 — Mismo VIN en el mismo tenant

Crear dos veces el mismo VIN para A.

**Esperado:** `400` o `409` según el handler activo; no se crean duplicados.

## 6. Eventos Kafka de Integration Profiles

Consumir antes de ejecutar las operaciones para observar eventos nuevos:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server kafka:9092 --topic integration-profile.events `
  --from-beginning --timeout-ms 15000
```

### KAF-01 — Evento de creación

Ejecutar IP-01.

**Esperado:** evento `integration-profile.created` después del commit, con `profileId`, `tenantId` y payload del perfil.

### KAF-02 — Evento de actualización

Ejecutar IP-08.

**Esperado:** evento `integration-profile.updated`, con versión nueva y tenant correcto.

### KAF-03 — Evento de desactivación

Ejecutar IP-11.

**Esperado:** evento `integration-profile.deactivated`, con `active=false` o representación equivalente.

### KAF-04 — Evento no emitido cuando falla la transacción

Provocar validación fallida o conflicto de versión.

**Esperado:** no se publica un evento de éxito para la operación fallida.

### KAF-05 — Clave Kafka

Inspeccionar el registro con un consumidor que muestre la clave.

**Esperado:** la clave corresponde al `profileId`, permitiendo orden por perfil.

## 7. API de vehículos

### VEH-01 — Crear vehículo válido

```powershell
curl.exe -i -X POST http://localhost:8081/api/v1/vehicles `
  -H "Authorization: Bearer $env:ACCESS_TOKEN" -H 'Content-Type: application/json' `
  --data '{"vin":"VIN-001","brandCode":"TOYOTA","modelCode":"COROLLA","modelYear":2025}'
```

**Esperado:** `201`; guardar `id` como `VEHICLE_ID`, `active=true`, `version=0`.

### VEH-02 — Validar VIN, marca y modelo obligatorios

Enviar campos ausentes, nulos, vacíos o solo espacios.

**Esperado:** `400`, sin fila ni outbox creado.

### VEH-03 — Validar rango de año

Probar `modelYear=1885` y `modelYear=3001`.

**Esperado:** `400`; `1886` y `3000` son valores límite válidos.

### VEH-04 — Listar vehículos activos

```powershell
curl.exe -i http://localhost:8081/api/v1/vehicles `
  -H "Authorization: Bearer $env:ACCESS_TOKEN"
```

**Esperado:** `200`; solo vehículos del tenant autenticado y activos.

### VEH-05 — Obtener vehículo por ID

**Esperado:** `200` para el tenant propietario y `404` para otro tenant.

### VEH-06 — VIN duplicado en el mismo tenant

Repetir VEH-01 con `VIN-001`.

**Esperado:** rechazo; no se duplica el vehículo ni el evento outbox.

### VEH-07 — Mismo VIN en otro tenant

Crear `VIN-001` con `TENANT_B`.

**Esperado:** permitido y aislado de `TENANT_A`.

### VEH-08 — Inyección de tenant por body/header

Enviar campos adicionales como `tenantId` en el JSON o un `X-Tenant-ID` distinto al tenant autenticado.

**Esperado:** el tenant efectivo proviene de la autenticación/Gateway; el body no controla el tenant.

## 8. Outbox, Inbox y publicación de vehículo

### OUT-01 — Outbox transaccional en creación

Después de VEH-01:

```powershell
docker compose exec mysql mysql -uintegration -pintegration integration `
  -e "SELECT id, tenant_id, aggregate_type, aggregate_id, event_type, status, attempts, published_at FROM integration_outbox ORDER BY created_at DESC LIMIT 5;"
```

**Esperado:** registro `Vehicle`, `vehicle.created`, tenant correcto y estado inicial `PENDING` o el estado configurado por el publisher.

### OUT-02 — Payload del outbox

```powershell
docker compose exec mysql mysql -uintegration -pintegration integration `
  -e "SELECT JSON_PRETTY(payload) FROM integration_outbox ORDER BY created_at DESC LIMIT 1;"
```

**Esperado:** contiene `eventId`, `tenantId`, `vehicleId`, `eventType=vehicle.created`, VIN, marca, modelo, año y estado activo.

### OUT-03 — Publicación Kafka del outbox

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server kafka:9092 --topic integration.events `
  --from-beginning --timeout-ms 15000
```

**Esperado:** aparece el evento `vehicle.created` correspondiente al vehículo.

### OUT-04 — Estado publicado

Consultar nuevamente `integration_outbox`.

**Esperado:** `status` publicado, `published_at` informado y `last_error` vacío.

### OUT-05 — Reintento de publicación

Detener Kafka temporalmente, crear un vehículo, restaurar Kafka y observar el outbox.

**Esperado:** aumenta `attempts`, se conserva el evento y se publica cuando Kafka vuelve; no se pierde el registro.

### OUT-06 — Idempotencia Inbox

Procesar dos veces el mismo `event_id` en el componente Inbox o repetir el mensaje en el consumidor.

**Esperado:** una sola aceptación efectiva; el segundo procesamiento no duplica efectos.

### OUT-07 — Aislamiento de outbox

Consultar registros de outbox de A y B.

**Esperado:** cada evento conserva el `tenant_id` de su vehículo y no mezcla payloads entre tenants.

## 9. Persistencia y consistencia

### DB-01 — Tablas y claves

```powershell
docker compose exec mysql mysql -uintegration -pintegration integration `
  -e "SHOW TABLES; SHOW CREATE TABLE integration_profile; SHOW CREATE TABLE vehicle;"
```

**Esperado:** existen las tablas migradas, índices tenant-scoped y restricciones de unicidad.

### DB-02 — Reinicio de app conserva datos

Crear un perfil y vehículo, ejecutar `docker compose restart app`, y consultar nuevamente.

**Esperado:** datos y estados permanecen.

### DB-03 — Reinicio sin borrar volumen

```powershell
docker compose down --remove-orphans
docker compose up -d mysql redis kafka app middleware
```

**Esperado:** datos conservados y migraciones no duplicadas.

### DB-04 — Inicialización limpia

> Ejecutar solo si se permite eliminar los datos locales de este Compose.

```powershell
docker compose down -v --remove-orphans
docker compose up -d --build mysql redis kafka app middleware
```

**Esperado:** esquema creado desde cero y todos los servicios saludables.

### DB-05 — Concurrencia de actualización

Enviar simultáneamente dos PUT con el mismo `expectedVersion`.

**Esperado:** exactamente una actualización gana; la otra recibe `409` y no sobrescribe cambios.

## 10. Cierre y evidencias

### CLS-01 — Logs sin secretos

```powershell
docker compose logs --no-color middleware app | Select-String -Pattern 'Bearer|access_token|password' -CaseSensitive:$false
```

**Esperado:** no aparecen tokens, contraseñas ni headers de autorización.

### CLS-02 — Estado final

```powershell
docker compose ps
docker compose config --quiet
```

**Esperado:** servicios saludables y configuración válida.

### CLS-03 — Limpieza conservando datos

```powershell
docker compose down --remove-orphans
```

**Esperado:** contenedores detenidos; volúmenes conservados.

### CLS-04 — Limpieza completa local

```powershell
docker compose down -v --remove-orphans
```

**Esperado:** contenedores, redes y volúmenes del proyecto eliminados. No ejecutar `docker system prune` para evitar afectar otros proyectos.

## 11. Registro de ejecución

| ID | Resultado | Fecha | Ejecutor | Evidencia / observación |
|---|---|---|---|---|
| PRE-01 |  |  |  |  |
| PRE-02 |  |  |  |  |
| SEC-01 |  |  |  |  |
| SEC-02 |  |  |  |  |
| IP-01 |  |  |  |  |
| IP-08 |  |  |  |  |
| TEN-02 |  |  |  |  |
| KAF-01 |  |  |  |  |
| VEH-01 |  |  |  |  |
| OUT-03 |  |  |  |  |
| DB-02 |  |  |  |  |
| CLS-02 |  |  |  |  |
