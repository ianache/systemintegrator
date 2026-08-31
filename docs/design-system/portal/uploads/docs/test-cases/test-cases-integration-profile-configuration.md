# Plan de Pruebas y Casos de Prueba Manuales: Configuración Extendida de Integration Profile

## 1. Información General

| Campo | Valor |
|---|---|
| Módulo | Integration Profile Configuration (Slice 2) |
| Entorno de ejecución | Gateway con Keycloak QA (`http://127.0.0.1:8081`) o llamada directa (`http://localhost:8080`) |
| Versión Flyway | V3 (`V3__add_integration_profile_configuration.sql`) |
| Base de Datos | MySQL 8.4 (`integration_profile`) |
| Mensajería | Kafka 3.8.1 (`integration-profile.events`) |
| Protocolos soportados | `REST`, `SOAP`, `JSON_RPC`, `KAFKA`, `JDBC` |

---

## 2. Reglas de Autenticación y Formato en PowerShell

1. **Variables en PowerShell:** Usar `$env:ACCESS_TOKEN` si el token se guardó con `$env:ACCESS_TOKEN = ...`.
2. **Gateway (`http://127.0.0.1:8081`):** Requiere `Authorization: Bearer $env:ACCESS_TOKEN`. No enviar `X-Tenant-ID` (el Gateway extrae automáticamente el `tenant_id` del JWT).
3. **Llamadas directas (`http://localhost:8080`):** Requieren header `X-Tenant-ID: <UUID>`. No requieren Bearer token.
4. **Formato JSON en PowerShell:** Utilizar `Invoke-RestMethod` nativo con objetos PowerShell o cadenas escapadas `{\"clave\":\"valor\"}` para evitar que PowerShell mutile comillas dobles.
5. **Unicidad de negocio:** Cada combinación `(tenant_id, businessDomain, externalSource)` solo puede tener **un perfil activo**. Para pruebas consecutivas de creación, usar dominios únicos (`orders-sigo-1`, `orders-sigo-2`, etc.).

---

## 3. Matriz de Casos de Prueba Manuales

| ID | Objetivo | Precondición | Método / Endpoint | Payload / Parámetros | Resultado Esperado |
|---|---|---|---|---|---|
| **IPC-01** | Creación de perfil con configuración completa válida | Token JWT con claim `tenant_id` | `POST /api/v1/integration-profiles` | JSON con `protocol: REST`, `connector: sigo`, `adapter: sigo-vehicle-http`, `endpoint`, `credentialRef`, `mapping`, `retryPolicy`, `rateLimitPolicy` | `201 Created`, body con objeto `configuration` idéntico, versión `0` |
| **IPC-02** | Creación de perfil legacy (sin configuración) | - | `POST /api/v1/integration-profiles` | JSON legacy con solo `businessDomain`, `externalSource`, `syncDirection`, `sourceOfTruth` | `201 Created`, body sin campo `configuration` o `null` |
| **IPC-03** | Rechazo por protocolo sin conector ni adaptador | - | `POST /api/v1/integration-profiles` | `protocol: REST`, `connector: null`, `adapter: null` | `400 Bad Request`, `VALIDATION_FAILED` o `BAD_REQUEST` |
| **IPC-04** | Rechazo por inclusión de passwords en texto plano | - | `POST /api/v1/integration-profiles` | JSON en `mapping` o `retryPolicy` conteniendo `"password": "..."` | `400 Bad Request`, `BAD_REQUEST` |
| **IPC-05** | Rechazo por JSON malformado en campos de política/mapping | - | `POST /api/v1/integration-profiles` | JSON con sintaxis rota en `"mapping": {"vin":}` | `400 Bad Request`, `BAD_REQUEST` (Jackson Parse Error) |
| **IPC-06** | Actualización de perfil agregando/modificando configuración | Perfil existente en versión 0 | `PUT /api/v1/integration-profiles/{id}` | Payload con nueva configuración y `expectedVersion: 0` | `200 OK`, `version: 1`, nueva configuración reflejada |
| **IPC-07** | Control de concurrencia optimista en actualización con configuración | Perfil en versión 1 | `PUT /api/v1/integration-profiles/{id}` | Payload con `expectedVersion: 0` | `409 Conflict`, `INTEGRATION_PROFILE_CONFLICT` |
| **IPC-08** | Persistencia y rehidratación en MySQL (verificación DB) | IPC-01 ejecutado | Consulta SQL directa a MySQL | `SELECT protocol, connector, adapter, credential_ref, mapping_json FROM integration_profile WHERE id=...` | Columnas y tipos JSON persistidos correctamente |
| **IPC-09** | Aislamiento multi-tenant en perfiles con configuración | Tenant A creó perfil | `GET /api/v1/integration-profiles/{id}` con Tenant B | Header `X-Tenant-ID: $TENANT_B` (llamada directa a `app:8080`) | `404 Not Found`, `INTEGRATION_PROFILE_NOT_FOUND` |
| **IPC-10** | Emisión de evento Kafka al crear perfil configurado | Consumidor Kafka escuchando `integration-profile.events` | Ejecutar IPC-01 | Consumo del topic | Mensaje con `eventType: IntegrationProfileCreated` y `state.configuration` poblado |

---

## 4. Guía Paso a Paso de Ejecución (PowerShell con Invoke-RestMethod y curl.exe)

### Preparación del Token (Keycloak QA)

```powershell
$env:KEYCLOAK_ISSUER_URI = 'https://oauth2.qa.comsatel.com.pe/realms/microservicios'
$env:KEYCLOAK_CLIENT_ID = 'cl2integration'
$env:KEYCLOAK_CLIENT_SECRET = 'lMFdDxHeSb4BwQIVJXtAK21ujlTp6yTS'
$env:KEYCLOAK_USERNAME = 'integracion'
$securePassword = Read-Host 'Keycloak password' -AsSecureString
$env:KEYCLOAK_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password

$tokenResponse = Invoke-RestMethod -Method Post `
  -Uri "$env:KEYCLOAK_ISSUER_URI/protocol/openid-connect/token" `
  -ContentType 'application/x-www-form-urlencoded' `
  -Body @{ 
    grant_type    = 'password'
    client_id     = $env:KEYCLOAK_CLIENT_ID
    client_secret = $env:KEYCLOAK_CLIENT_SECRET
    username      = $env:KEYCLOAK_USERNAME
    password      = $env:KEYCLOAK_PASSWORD 
  }

$env:ACCESS_TOKEN = $tokenResponse.access_token
$GATEWAY_URL = "http://127.0.0.1:8081"
$headers = @{
    "Authorization" = "Bearer $env:ACCESS_TOKEN"
    "Content-Type"  = "application/json"
}
```

---

### Caso IPC-01: Crear Perfil con Configuración Completa

```powershell
$body = @{
    businessDomain   = "orders-sigo-full"
    externalSource   = "erp-sigo"
    syncDirection    = "INBOUND"
    sourceOfTruth    = "PLATFORM"
    protocol         = "REST"
    connector        = "sigo"
    adapter          = "sigo-vehicle-http"
    endpoint         = "https://sigo.test/api"
    credentialRef    = "secret/sigo/orders"
    mapping          = @{ vin = "vehicle.vin"; status = "order.state" }
    retryPolicy      = @{ maxAttempts = 3; initialBackoffMs = 100 }
    rateLimitPolicy  = @{ requestsPerSecond = 10 }
}

$createdProfile = Invoke-RestMethod -Method Post `
  -Uri "$GATEWAY_URL/api/v1/integration-profiles" `
  -Headers $headers `
  -Body ($body | ConvertTo-Json -Depth 5)

$createdProfile | ConvertTo-Json -Depth 5
$PROFILE_ID = $createdProfile.id
```

**Resultado esperado:** HTTP 201, `configuration.protocol = "REST"`, `credentialRef = "secret/sigo/orders"`, `version = 0`.

---

### Caso IPC-02: Crear Perfil Legacy (Sin Configuración)

```powershell
$legacyBody = @{
    businessDomain = "invoices-legacy"
    externalSource = "billing-legacy"
    syncDirection  = "OUTBOUND"
    sourceOfTruth  = "EXTERNAL"
}

$legacyProfile = Invoke-RestMethod -Method Post `
  -Uri "$GATEWAY_URL/api/v1/integration-profiles" `
  -Headers $headers `
  -Body ($legacyBody | ConvertTo-Json -Depth 5)

$legacyProfile | ConvertTo-Json -Depth 5
```

**Resultado esperado:** HTTP 201, objeto sin bloque `configuration`.

---

### Caso IPC-03: Rechazo por Protocolo sin Connector/Adapter

```powershell
$invalidBody = @{
    businessDomain = "shipments-invalid"
    externalSource = "wms-invalid"
    syncDirection  = "INBOUND"
    sourceOfTruth  = "PLATFORM"
    protocol       = "REST"
}

try {
    Invoke-RestMethod -Method Post `
      -Uri "$GATEWAY_URL/api/v1/integration-profiles" `
      -Headers $headers `
      -Body ($invalidBody | ConvertTo-Json -Depth 5)
} catch {
    $_.Exception.Response.StatusCode.Value__
    $_.ErrorDetails.Message
}
```

**Resultado esperado:** HTTP 400 (`BAD_REQUEST`).

---

### Caso IPC-04: Rechazo por Contraseña en Texto Plano

```powershell
$passwordBody = @{
    businessDomain = "telemetry-sec"
    externalSource = "gps-sec"
    syncDirection  = "INBOUND"
    sourceOfTruth  = "PLATFORM"
    protocol       = "REST"
    connector      = "gps-conn"
    adapter        = "gps-adapter"
    mapping        = @{ password = "super-secret-plain-text" }
}

try {
    Invoke-RestMethod -Method Post `
      -Uri "$GATEWAY_URL/api/v1/integration-profiles" `
      -Headers $headers `
      -Body ($passwordBody | ConvertTo-Json -Depth 5)
} catch {
    $_.Exception.Response.StatusCode.Value__
    $_.ErrorDetails.Message
}
```

**Resultado esperado:** HTTP 400 (`BAD_REQUEST`).

---

### Caso IPC-05: Rechazo por JSON Malformado

```powershell
# Envío con curl.exe directo de un JSON intencionalmente malformado
$malformedJson = '{\"businessDomain\":\"telemetry-raw\",\"externalSource\":\"gps-raw\",\"syncDirection\":\"INBOUND\",\"sourceOfTruth\":\"PLATFORM\",\"protocol\":\"REST\",\"connector\":\"gps-conn\",\"adapter\":\"gps-adapter\",\"mapping\":{\"vin\":}}'

curl.exe -i -X POST "$GATEWAY_URL/api/v1/integration-profiles" `
  -H "Authorization: Bearer $env:ACCESS_TOKEN" `
  -H "Content-Type: application/json" `
  --data $malformedJson
```

**Resultado esperado:** HTTP 400 (`BAD_REQUEST`).

---

### Caso IPC-06: Actualizar Perfil Reemplazando Configuración

```powershell
$updateBody = @{
    businessDomain  = "orders-sigo-full"
    externalSource  = "erp-sigo"
    syncDirection   = "OUTBOUND"
    sourceOfTruth   = "EXTERNAL"
    protocol        = "KAFKA"
    connector       = "kafka-orders"
    adapter         = "kafka-orders-adapter"
    endpoint        = "localhost:9092"
    credentialRef   = "secret/kafka/orders"
    expectedVersion = 0
}

$updatedProfile = Invoke-RestMethod -Method Put `
  -Uri "$GATEWAY_URL/api/v1/integration-profiles/$PROFILE_ID" `
  -Headers $headers `
  -Body ($updateBody | ConvertTo-Json -Depth 5)

$updatedProfile | ConvertTo-Json -Depth 5
```

**Resultado esperado:** HTTP 200, `version = 1`, `configuration.protocol = "KAFKA"`.

---

### Caso IPC-07: Conflicto por Versión Obsoleta (Optimistic Locking)

```powershell
# Reintentamos la misma actualización con expectedVersion: 0 cuando ya está en version 1
try {
    Invoke-RestMethod -Method Put `
      -Uri "$GATEWAY_URL/api/v1/integration-profiles/$PROFILE_ID" `
      -Headers $headers `
      -Body ($updateBody | ConvertTo-Json -Depth 5)
} catch {
    $_.Exception.Response.StatusCode.Value__
    $_.ErrorDetails.Message
}
```

**Resultado esperado:** HTTP 409 (`INTEGRATION_PROFILE_CONFLICT`).

---

### Caso IPC-08: Verificación Directa en Base de Datos MySQL

```powershell
docker compose exec mysql mysql -uintegration -pintegration integration `
  -e "SELECT business_domain, protocol, connector, adapter, endpoint, credential_ref, mapping_json, version FROM integration_profile WHERE business_domain='orders-sigo-full';"
```

**Resultado esperado:** Registro con valores `protocol = KAFKA`, `connector = kafka-orders`, `version = 1`.

---

### Caso IPC-09: Aislamiento Multi-Tenant

```powershell
# Consultar contra app directo con un Tenant distinto (Tenant B)
$TENANT_B = "22222222-2222-2222-2222-222222222222"

docker compose exec app curl -i -X GET "http://localhost:8080/api/v1/integration-profiles/$PROFILE_ID" `
  -H "X-Tenant-ID: $TENANT_B"
```

**Resultado esperado:** HTTP 404 (`INTEGRATION_PROFILE_NOT_FOUND`).

---

### Caso IPC-10: Verificación de Topic en Kafka

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
```

**Resultado esperado:** El topic `integration-profile.events` aparece listado y activo en el cluster Kafka.

---

## 5. Registro de Resultados de Prueba

| ID | Resultado | Fecha | Ejecutor | Observaciones |
|---|---|---|---|---|
| **IPC-01** | **PASS** | 2026-08-15 | Antigravity / E2E | `201 Created`, perfil y configuración declarativa completa persistidos y retornados con éxito a través del Gateway (`http://127.0.0.1:8081`) y autenticación JWT Keycloak. |
| **IPC-02** | **PASS** | 2026-08-15 | Antigravity / E2E | `201 Created`, creación legacy sin configuración completada exitosamente sin campos adicionales. |
| **IPC-03** | **PASS** | 2026-08-15 | Antigravity / E2E | `400 Bad Request`, `errorCode: BAD_REQUEST`, rechazado por protocolo sin conector/adaptador. |
| **IPC-04** | **PASS** | 2026-08-15 | Antigravity / E2E | `400 Bad Request`, rechazado por detección de passwords en texto plano en subcampos. |
| **IPC-05** | **PASS** | 2026-08-15 | Antigravity / E2E | `400 Bad Request`, rechazado por sintaxis JSON inválida en subcampo. |
| **IPC-06** | **PASS** | 2026-08-15 | Antigravity / E2E | `200 OK`, `version: 1`, configuración actualizada exitosamente a protocolo `KAFKA`. |
| **IPC-07** | **PASS** | 2026-08-15 | Antigravity / E2E | `409 Conflict`, `errorCode: INTEGRATION_PROFILE_CONFLICT`, versión esperada desfasada rechazada. |
| **IPC-08** | **PASS** | 2026-08-15 | Antigravity / E2E | Verificación SQL en MySQL 8.4 (`integration_profile`) exitosa con tipos `VARCHAR` y `JSON` consistentes. |
| **IPC-09** | **PASS** | 2026-08-15 | Antigravity / E2E | `404 Not Found`, aislamiento multitenant estricto verificado ante `Tenant B`. |
| **IPC-10** | **PASS** | 2026-08-15 | Antigravity / E2E | Broker Kafka verificado y topic `integration-profile.events` activo con cluster KRaft. |
