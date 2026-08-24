# Guía de pruebas manuales E2E

Esta guía valida el flujo completo **cliente → Gateway → aplicación → MySQL/Kafka**, usando el Keycloak QA existente.

> El nombre del archivo conserva `manul` para respetar el nombre solicitado.

## 1. Precondiciones

- Docker Desktop/Engine iniciado y accesible por el usuario actual.
- Puertos libres: `3306`, `6379`, `29092` y `8081`.
- Acceso al realm `microservicios` de Keycloak QA:
  `https://oauth2.qa.comsatel.com.pe/realms/microservicios`
- Usuario QA autorizado. No guardar la contraseña, token ni respuestas de autenticación en el repositorio.
- El JWT debe contener `tenant_id` con formato UUID. Si el usuario `user` no tiene ese claim, solicitar un usuario QA con el mapper/atributo correspondiente; el Gateway rechazará la llamada con `403`.

## 2. Levantar el entorno completo

Desde la raíz del repositorio, en PowerShell:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'qa-e2e'
$env:KEYCLOAK_ISSUER_URI = 'https://oauth2.qa.comsatel.com.pe/realms/microservicios'

docker compose config --quiet
docker compose up -d --build mysql redis kafka app middleware
docker compose ps
```

Esperado: `mysql`, `redis`, `kafka`, `app` y `middleware` en estado `running`/`healthy`.

Comprobaciones rápidas:

```powershell
docker compose exec mysql mysqladmin ping -uintegration -pintegration --silent
docker compose exec redis redis-cli ping
curl.exe -fsS http://localhost:8081/actuator/health
```

La respuesta del healthcheck del Gateway debe indicar `UP`.

## 3. Obtener un token QA

Definir las credenciales solo en la sesión local. `admin-cli` es el valor habitual; cambiarlo si el realm usa otro cliente con *Direct Access Grants* habilitado.

```powershell
$env:KEYCLOAK_CLIENT_ID = 'admin-cli'
$env:KEYCLOAK_USERNAME = 'user'
$securePassword = Read-Host 'Keycloak password' -AsSecureString
$env:KEYCLOAK_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password

$tokenResponse = Invoke-RestMethod `
  -Method Post `
  -Uri "$env:KEYCLOAK_ISSUER_URI/protocol/openid-connect/token" `
  -ContentType 'application/x-www-form-urlencoded' `
  -Body @{
    grant_type = 'password'
    client_id = $env:KEYCLOAK_CLIENT_ID
    username = $env:KEYCLOAK_USERNAME
    password = $env:KEYCLOAK_PASSWORD
  }

$env:ACCESS_TOKEN = $tokenResponse.access_token
```

No imprimir `$tokenResponse` ni `$env:ACCESS_TOKEN`. Si Keycloak responde `unauthorized_client` o `invalid_grant`, revisar el cliente, el acceso directo de usuario y las credenciales.

## 4. Casos de prueba

### CP-01 — Health del Gateway

```powershell
curl.exe -i http://localhost:8081/actuator/health
```

Esperado: HTTP `200` y estado `UP`.

### CP-02 — Rechazo sin token

```powershell
curl.exe -i http://localhost:8081/api/v1/integration-profiles
```

Esperado: HTTP `401`.

### CP-03 — Rechazo con token inválido

```powershell
curl.exe -i http://localhost:8081/api/v1/integration-profiles `
  -H 'Authorization: Bearer token-invalido'
```

Esperado: HTTP `401`.

### CP-04 — Crear perfil por Gateway

```powershell
curl.exe -i -X POST http://localhost:8081/api/v1/integration-profiles `
  -H "Authorization: Bearer $env:ACCESS_TOKEN" `
  -H 'Content-Type: application/json' `
  -H 'X-Tenant-ID: 00000000-0000-0000-0000-000000000000' `
  --data '{"businessDomain":"orders","externalSource":"erp","syncDirection":"INBOUND","sourceOfTruth":"PLATFORM"}'
```

Esperado: HTTP `201`. El `X-Tenant-ID` falso debe ser ignorado; la persistencia debe usar el `tenant_id` del JWT.

### CP-05 — Listar perfiles del tenant autenticado

```powershell
curl.exe -i http://localhost:8081/api/v1/integration-profiles `
  -H "Authorization: Bearer $env:ACCESS_TOKEN"
```

Esperado: HTTP `200` y el perfil creado en CP-04.

### CP-06 — Actualizar con control de versión

Usar el `id` y `version` devueltos por CP-04:

```powershell
curl.exe -i -X PUT "http://localhost:8081/api/v1/integration-profiles/<PROFILE_ID>" `
  -H "Authorization: Bearer $env:ACCESS_TOKEN" `
  -H 'Content-Type: application/json' `
  --data '{"expectedVersion":0,"businessDomain":"orders-updated","externalSource":"erp","syncDirection":"INBOUND","sourceOfTruth":"PLATFORM"}'
```

Esperado: HTTP `200` y versión incrementada. Repetir con la versión anterior debe producir el conflicto de versión esperado (`409`).

### CP-07 — Desactivación lógica

```powershell
curl.exe -i -X DELETE "http://localhost:8081/api/v1/integration-profiles/<PROFILE_ID>" `
  -H "Authorization: Bearer $env:ACCESS_TOKEN"
```

Esperado: HTTP `204` o el código definido por el contrato vigente. El registro no debe eliminarse físicamente.

### CP-08 — Aislamiento por tenant

Repetir CP-03 a CP-07 con un token cuyo `tenant_id` sea otro UUID autorizado. No enviar `X-Tenant-ID` o enviarlo con el UUID del primer tenant.

Esperado: el segundo tenant no puede leer, modificar ni desactivar el perfil del primero; la respuesta debe ser `404` o `403` según el contrato de la operación. El header del cliente nunca cambia el tenant efectivo.

### CP-09 — Eventos Kafka

Verificar los eventos del perfil en `integration-profile.events`:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server kafka:9092 `
  --topic integration-profile.events `
  --from-beginning `
  --timeout-ms 15000
```

Esperado: eventos de creación, actualización y desactivación con `profileId`, `tenantId` igual al claim autenticado y el `eventType` correspondiente.

## 5. Diagnóstico

```powershell
docker compose ps
docker compose logs --no-color --tail=200 app
docker compose logs --no-color --tail=200 middleware
docker compose logs --no-color --tail=200 kafka
docker info
```

- `//./pipe/docker_engine` o `permission denied`: Docker Desktop/Engine no está disponible para el usuario actual.
- `app` no saludable: revisar MySQL, Redis, Kafka y las migraciones Flyway.
- `middleware` no saludable en perfil `qa-e2e`: revisar conectividad HTTPS al issuer y `KEYCLOAK_ISSUER_URI`.
- `403` con token válido: comprobar que el JWT contiene `tenant_id` UUID.
- `401`: comprobar expiración del token, issuer, cliente y firma.

## 6. Detener y limpiar

Para detener conservando datos:

```powershell
docker compose down --remove-orphans
```

Para una ejecución limpia, eliminando únicamente los volúmenes de este Compose:

```powershell
docker compose down -v --remove-orphans
```

Limpiar variables sensibles al terminar la sesión:

```powershell
Remove-Item Env:ACCESS_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:KEYCLOAK_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:KEYCLOAK_USERNAME -ErrorAction SilentlyContinue
```
