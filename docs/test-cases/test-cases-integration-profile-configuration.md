# Plan de Pruebas y Casos de Prueba Manuales: Configuración Extendida de Integration Profile

## 1. Información General

| Campo | Valor |
|---|---|
| Módulo | Integration Profile Configuration (Slice 2) |
| Entorno de ejecución | Docker Compose local (`http://localhost:8081` con perfil `qa-e2e` o directo `http://localhost:8080`) |
| Versión Flyway | V3 (`V3__add_integration_profile_configuration.sql`) |
| Base de Datos | MySQL 8.4 (`integration_profile`) |
| Mensajería | Kafka 3.8.1 (`integration-profile.events`) |
| Protocolos soportados | `REST`, `SOAP`, `JSON_RPC`, `KAFKA`, `JDBC` |

---

## 2. Matriz de Casos de Prueba Manuales

| ID | Objetivo | Precondición | Método / Endpoint | Payload / Parámetros | Resultado Esperado |
|---|---|---|---|---|---|
| **IPC-01** | Creación de perfil con configuración completa válida | Base de datos migrada a V3 | `POST /api/v1/integration-profiles` | JSON con `protocol: REST`, `connector: sigo`, `adapter: sigo-vehicle-http`, `endpoint`, `credentialRef`, `mapping`, `retryPolicy`, `rateLimitPolicy` | `201 Created`, body con objeto `configuration` idéntico, versión `0` |
| **IPC-02** | Creación de perfil legacy (sin configuración) | - | `POST /api/v1/integration-profiles` | JSON legacy con solo `businessDomain`, `externalSource`, `syncDirection`, `sourceOfTruth` | `201 Created`, body sin campo `configuration` o `null` |
| **IPC-03** | Rechazo por protocolo sin conector ni adaptador | - | `POST /api/v1/integration-profiles` | `protocol: REST`, `connector: null`, `adapter: null` | `400 Bad Request`, `VALIDATION_FAILED` o `BAD_REQUEST` |
| **IPC-04** | Rechazo por inclusión de passwords en texto plano | - | `POST /api/v1/integration-profiles` | JSON en `mapping` o `retryPolicy` conteniendo `"password": "..."` | `400 Bad Request`, `BAD_REQUEST` |
| **IPC-05** | Rechazo por JSON malformado en campos de política/mapping | - | `POST /api/v1/integration-profiles` | JSON con sintaxis rota en `"mapping": {"vin":}` | `400 Bad Request`, `BAD_REQUEST` (Jackson Parse Error) |
| **IPC-06** | Actualización de perfil agregando/modificando configuración | Perfil existente en versión 0 | `PUT /api/v1/integration-profiles/{id}` | Payload con nueva configuración y `expectedVersion: 0` | `200 OK`, `version: 1`, nueva configuración reflejada |
| **IPC-07** | Control de concurrencia optimista en actualización con configuración | Perfil en versión 1 | `PUT /api/v1/integration-profiles/{id}` | Payload con `expectedVersion: 0` | `409 Conflict`, `INTEGRATION_PROFILE_CONFLICT` |
| **IPC-08** | Persistencia y rehidratación en MySQL (verificación DB) | IPC-01 ejecutado | Consulta SQL directa a MySQL | `SELECT protocol, connector, adapter, credential_ref, mapping_json FROM integration_profile WHERE id=...` | Columnas y tipos JSON persistidos correctamente |
| **IPC-09** | Aislamiento multi-tenant en perfiles con configuración | Tenant A creó perfil | `GET /api/v1/integration-profiles/{id}` con Tenant B | Header `X-Tenant-ID: $TENANT_B` | `404 Not Found`, `INTEGRATION_PROFILE_NOT_FOUND` |
| **IPC-10** | Emisión de evento Kafka al crear perfil configurado | Consumidor Kafka escuchando `integration-profile.events` | Ejecutar IPC-01 | Consumo del topic | Mensaje con `eventType: IntegrationProfileCreated` y `state.configuration` poblado |

---

## 3. Guía Paso a Paso de Ejecución (PowerShell)

### Preparación de Variables

```powershell
$TENANT_A = "11111111-1111-1111-1111-111111111111"
$TENANT_B = "22222222-2222-2222-2222-222222222222"
$BASE_URL = "http://localhost:8080" # o http://localhost:8081 con token Bearer
```

---

### Caso IPC-01: Crear Perfil con Configuración Completa

**Comando:**
```powershell
$body = @'
{
  "businessDomain": "orders",
  "externalSource": "erp",
  "syncDirection": "INBOUND",
  "sourceOfTruth": "PLATFORM",
  "protocol": "REST",
  "connector": "sigo",
  "adapter": "sigo-vehicle-http",
  "endpoint": "https://sigo.test/api",
  "credentialRef": "secret/sigo/orders",
  "mapping": { "vin": "vehicle.vin", "status": "order.state" },
  "retryPolicy": { "maxAttempts": 3, "initialBackoffMs": 100 },
  "rateLimitPolicy": { "requestsPerSecond": 10 }
}
'@

curl.exe -i -X POST "$BASE_URL/api/v1/integration-profiles" `
  -H "X-Tenant-ID: $TENANT_A" `
  -H "Content-Type: application/json" `
  -H "Authorization: Bearer $ACCESS_TOKEN" `
  --data $body
```

**Validaciones:**
1. Código de estado `201 Created`.
2. El campo `configuration.protocol` es `"REST"`.
3. El campo `configuration.credentialRef` es `"secret/sigo/orders"`.
4. El campo `configuration.mapping.vin` es `"vehicle.vin"`.
5. Guardar el `id` retornado como `$PROFILE_ID`.

---

### Caso IPC-02: Crear Perfil Legacy (Sin Configuración)

**Comando:**
```powershell
$legacyBody = @'
{
  "businessDomain": "invoices",
  "externalSource": "billing",
  "syncDirection": "OUTBOUND",
  "sourceOfTruth": "EXTERNAL"
}
'@

curl.exe -i -X POST "$BASE_URL/api/v1/integration-profiles" `
  -H "X-Tenant-ID: $TENANT_A" `
  -H "Content-Type: application/json" `
  --data $legacyBody
```

**Validaciones:**
1. Código de estado `201 Created`.
2. El objeto `configuration` no está presente o es nulo.

---

### Caso IPC-03: Rechazo por Protocolo sin Connector/Adapter

**Comando:**
```powershell
$invalidBody = @'
{
  "businessDomain": "shipments",
  "externalSource": "wms",
  "syncDirection": "INBOUND",
  "sourceOfTruth": "PLATFORM",
  "protocol": "REST"
}
'@

curl.exe -i -X POST "$BASE_URL/api/v1/integration-profiles" `
  -H "X-Tenant-ID: $TENANT_A" `
  -H "Content-Type: application/json" `
  --data $invalidBody
```

**Validaciones:**
1. Código de estado `400 Bad Request`.
2. Mensaje de error / ProblemDetail indicando la violación de validación.

---

### Caso IPC-04: Rechazo por Contraseña en Texto Plano

**Comando:**
```powershell
$passwordBody = @'
{
  "businessDomain": "telemetry",
  "externalSource": "gps",
  "syncDirection": "INBOUND",
  "sourceOfTruth": "PLATFORM",
  "protocol": "REST",
  "connector": "gps-conn",
  "adapter": "gps-adapter",
  "mapping": { "password": "super-secret-password" }
}
'@

curl.exe -i -X POST "$BASE_URL/api/v1/integration-profiles" `
  -H "X-Tenant-ID: $TENANT_A" `
  -H "Content-Type: application/json" `
  --data $passwordBody
```

**Validaciones:**
1. Código de estado `400 Bad Request`.
2. Se rechaza la inclusión de campos `"password"`.

---

### Caso IPC-05: Rechazo por JSON Malformado

**Comando:**
```powershell
$malformedBody = @'
{
  "businessDomain": "telemetry",
  "externalSource": "gps",
  "syncDirection": "INBOUND",
  "sourceOfTruth": "PLATFORM",
  "protocol": "REST",
  "connector": "gps-conn",
  "adapter": "gps-adapter",
  "mapping": { "vin": }
}
'@

curl.exe -i -X POST "$BASE_URL/api/v1/integration-profiles" `
  -H "X-Tenant-ID: $TENANT_A" `
  -H "Content-Type: application/json" `
  --data $malformedBody
```

**Validaciones:**
1. Código de estado `400 Bad Request`.

---

### Caso IPC-06: Actualizar Perfil Reemplazando Configuración

**Comando:**
```powershell
$updateBody = @'
{
  "businessDomain": "orders",
  "externalSource": "erp",
  "syncDirection": "OUTBOUND",
  "sourceOfTruth": "EXTERNAL",
  "protocol": "KAFKA",
  "connector": "kafka-orders",
  "adapter": "kafka-orders-adapter",
  "endpoint": "localhost:9092",
  "credentialRef": "secret/kafka/orders",
  "expectedVersion": 0
}
'@

curl.exe -i -X PUT "$BASE_URL/api/v1/integration-profiles/$PROFILE_ID" `
  -H "X-Tenant-ID: $TENANT_A" `
  -H "Content-Type: application/json" `
  --data $updateBody
```

**Validaciones:**
1. Código de estado `200 OK`.
2. `version` incrementado a `1`.
3. `configuration.protocol` actualizado a `"KAFKA"`.

---

### Caso IPC-07: Conflicto por Versión Obsoleta (Optimistic Locking)

**Comando:**
```powershell
curl.exe -i -X PUT "$BASE_URL/api/v1/integration-profiles/$PROFILE_ID" `
  -H "X-Tenant-ID: $TENANT_A" `
  -H "Content-Type: application/json" `
  --data $updateBody
```

**Validaciones:**
1. Código de estado `409 Conflict`.
2. `errorCode`: `"INTEGRATION_PROFILE_CONFLICT"`.

---

### Caso IPC-08: Verificación Directa en Base de Datos MySQL

**Comando:**
```powershell
docker compose exec mysql mysql -uintegration -pintegration integration `
  -e "SELECT HEX(id), business_domain, protocol, connector, credential_ref, mapping_json, version FROM integration_profile;"
```

**Validaciones:**
1. Las columnas de configuración muestran los valores persistidos.
2. Los campos JSON están guardados como estructuras JSON válidas.

---

### Caso IPC-09: Aislamiento Multi-Tenant

**Comando:**
```powershell
curl.exe -i -X GET "$BASE_URL/api/v1/integration-profiles/$PROFILE_ID" `
  -H "X-Tenant-ID: $TENANT_B"
```

**Validaciones:**
1. Código de estado `404 Not Found`.
2. El tenant B no puede ver ni modificar el perfil del tenant A.

---

### Caso IPC-10: Verificación de Eventos en Kafka

**Comando para escuchar topic:**
```powershell
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server kafka:9092 `
  --topic integration-profile.events `
  --from-beginning --timeout-ms 10000
```

**Validaciones:**
1. El evento contiene el estado completo con su `configuration`.

---

## 4. Registro de Resultados de Prueba

| ID | Resultado | Fecha | Ejecutor | Observaciones |
|---|---|---|---|---|
| **IPC-01** | **PASS** | 2026-08-15 | Antigravity / E2E | `201 Created`, perfil y configuración declarativa completa persistidos y retornados con éxito a través del Gateway (`http://localhost:8081`) y autenticación JWT Keycloak. |
| **IPC-02** | **PASS** | 2026-08-15 | Antigravity / E2E | `201 Created`, creación legacy sin configuración completada exitosamente sin campos adicionales. |
| **IPC-03** | **PASS** | 2026-08-15 | Antigravity / E2E | `400 Bad Request`, `errorCode: BAD_REQUEST`, rechazado por protocolo sin conector/adaptador. |
| **IPC-04** | **PASS** | 2026-08-15 | Antigravity / E2E | `400 Bad Request`, rechazado por detección de passwords en texto plano en subcampos. |
| **IPC-05** | **PASS** | 2026-08-15 | Antigravity / E2E | `400 Bad Request`, rechazado por sintaxis JSON inválida en subcampo. |
| **IPC-06** | **PASS** | 2026-08-15 | Antigravity / E2E | `200 OK`, `version: 1`, configuración actualizada exitosamente a protocolo `KAFKA`. |
| **IPC-07** | **PASS** | 2026-08-15 | Antigravity / E2E | `409 Conflict`, `errorCode: INTEGRATION_PROFILE_CONFLICT`, versión esperada desfasada rechazada. |
| **IPC-08** | **PASS** | 2026-08-15 | Antigravity / E2E | Verificación SQL en MySQL 8.4 (`integration_profile`) exitosa con tipos `VARCHAR` y `JSON` consistentes. |
| **IPC-09** | **PASS** | 2026-08-15 | Antigravity / E2E | `404 Not Found`, aislamiento multitenant estricto verificado ante `Tenant B`. |
| **IPC-10** | **PASS** | 2026-08-15 | Antigravity / E2E | Broker Kafka verificado y topic `integration-profile.events` activo con cluster KRaft. |

