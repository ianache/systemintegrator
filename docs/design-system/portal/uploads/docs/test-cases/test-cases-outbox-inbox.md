# Plan de Pruebas Manuales E2E: Transactional Outbox, Idempotent Inbox y Dead Letter Queue (DLQ)

## 1. Información General

| Campo | Valor |
|---|---|
| Módulo | Transactional Outbox & Idempotent Inbox Pipeline (Core Resilience) |
| Entorno de ejecución | Docker Compose (`mysql:8.4`, `apache/kafka:3.8.1`, `app:8080`, `middleware:8081`) |
| Versión Flyway | V4 (`V4__enhance_outbox_inbox_dlq.sql`) |
| Tablas principales | `integration_outbox`, `integration_inbox`, `vehicle` |
| Tópicos Kafka | `integration.events`, `integration.events.dlq` |
| Esquema de Concurrencia | `SELECT ... FOR UPDATE SKIP LOCKED` en relay de outbox |
| Esquema de Idempotencia | Primary Key `(event_id)` con validación por `tenant_id` y enrutamiento a DLQ |

---

## 2. Reglas de Ejecución y Formato en PowerShell

1. **Terminal de Ejecución:** Utilizar PowerShell en Windows.
2. **Tenants de Prueba:**
   ```text
   TENANT_A = 11111111-1111-1111-1111-111111111111
   TENANT_B = 22222222-2222-2222-2222-222222222222
   ```
3. **Llamadas Directas (`http://localhost:8080`):** Requieren cabecera `X-Tenant-ID: <UUID>`.
4. **Llamadas por Gateway (`http://localhost:8081`):** Requieren token JWT con `Authorization: Bearer <TOKEN>`.
5. **Comandos Docker Compose:** Todos los comandos están listos para copiar y pegar en PowerShell.

---

## 3. Matriz de Casos de Prueba Manuales

| ID | Objetivo | Precondición | Acción / Entrada | Resultado Esperado |
|---|---|---|---|---|
| **OIB-PRE-01** | Validar Infraestructura y Migración V4 | Docker Compose levantado | Consulta Flyway y estructura de tablas en MySQL | Flyway V4 `success = 1`, columnas `topic`, `payload` e índices creados. |
| **OIB-MAN-01** | Creación Atómica de Dominio + Outbox `PENDING` | `app` activa | Crear vehículo vía REST API (`POST /api/v1/vehicles`) | Entidad `vehicle` guardada y registro `integration_outbox` generado en estado `PENDING`. |
| **OIB-MAN-02** | Relay Autónomo de Outbox a Kafka | OIB-MAN-01 ejecutado | Dejar actuar el scheduler de relay (1s) | `integration_outbox` pasa a `PUBLISHED`, `published_at` poblado y evento presente en Kafka. |
| **OIB-MAN-03** | Consumo y Procesamiento Idempotente en Inbox | Mensaje publicado en Kafka | Enviar evento nuevo a `integration.events` | Registro en `integration_inbox` con `status = 'PROCESSED'` y `processed_at` no nulo. |
| **OIB-MAN-04** | Deduplicación de Mensaje Repetido | OIB-MAN-03 completado | Reinyectar el mismo `eventId` en Kafka | El inbox detecta duplicado (`status = PROCESSED`), no reejecuta dominio ni genera errores. |
| **OIB-MAN-05** | Falla de Procesamiento y Enrutamiento a DLQ | Consumidor Inbox activo | Publicar mensaje con payload corrupto / inválido | `integration_inbox` pasa a `DEAD_LETTER`, `last_error` grabado y payload publicado en `integration.events.dlq`. |
| **OIB-MAN-06** | Reintentos y Backoff de Relay ante Broker no disponible | Kafka detenido temporalmente | Insertar evento outbox manual | Intentos incrementan (`attempts > 0`), `available_at` postergado con backoff exponencial. |

---

## 4. Guía Paso a Paso de Ejecución (PowerShell)

### Fase 0: Preparación y Validación del Entorno

#### Paso 0.1 — Levantar Contenedores
```powershell
docker compose up -d --build mysql kafka redis app
docker compose ps
```
*Resultado Esperado:* Todos los servicios en estado `healthy` / `running`.

#### Paso 0.2 — Verificar Migración Flyway V4 e Índices
```powershell
docker compose exec mysql mysql -uintegration -pintegration integration -e "
SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;
SHOW INDEX FROM integration_outbox;
SHOW INDEX FROM integration_inbox;
"
```
*Resultado Esperado:* `V4` aplicado con `success = 1` y presencia de `idx_outbox_relay` y `idx_inbox_retry`.

---

### Caso OIB-MAN-01: Creación Atómica de Dominio + Outbox PENDING

#### Paso 1.1 — Enviar Solicitud de Creación de Vehículo
```powershell
$tenantA = "11111111-1111-1111-1111-111111111111"
$vinTest = "MANUAL-VIN-" + (Get-Random -Minimum 1000 -Maximum 9999)

$body = @{
    vin = $vinTest
    brandCode = "TOYOTA"
    modelCode = "COROLLA"
    modelYear = 2024
} | ConvertTo-Json

$response = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/vehicles" `
  -Headers @{ "X-Tenant-ID" = $tenantA } `
  -ContentType "application/json" `
  -Body $body

Write-Host "Vehículo Creado con ID:" $response.id
```

#### Paso 1.2 — Verificar Inserción en Tabla de Vehículos y Outbox
```powershell
docker compose exec mysql mysql -uintegration -pintegration integration -e "
SELECT BIN_TO_UUID(id) AS vehicle_id, vin, brand_code, model_code FROM vehicle WHERE vin = '$vinTest';
SELECT BIN_TO_UUID(id) AS outbox_id, aggregate_type, event_type, status, attempts, published_at FROM integration_outbox WHERE payload LIKE '%$vinTest%';
"
```
*Resultado Esperado:* El vehículo existe en `vehicle` y el outbox contiene el registro con `event_type = 'vehicle.created'`.

---

### Caso OIB-MAN-02: Verificación del Relay de Outbox a Kafka

#### Paso 2.1 — Comprobar Transición a `PUBLISHED`
```powershell
# Esperar 2 segundos para permitir al scheduler procesar el lote
Start-Sleep -Seconds 2

docker compose exec mysql mysql -uintegration -pintegration integration -e "
SELECT BIN_TO_UUID(id) AS outbox_id, event_type, status, attempts, published_at, last_error 
FROM integration_outbox 
WHERE payload LIKE '%$vinTest%';
"
```
*Resultado Esperado:* `status = 'PUBLISHED'`, `attempts = 0`, `published_at` con timestamp UTC y `last_error` en `NULL`.

#### Paso 2.2 — Verificar Recepción del Evento en el Topic Kafka
```powershell
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server localhost:9092 `
  --topic integration.events `
  --from-beginning `
  --max-messages 1 `
  --timeout-ms 5000 `
  --property print.headers=true `
  --property print.key=true
```
*Resultado Esperado:* Se visualiza la clave del mensaje (UUID), cabeceras `X-Tenant-ID` y `X-Event-Type: vehicle.created`, y el payload JSON del vehículo.

---

### Caso OIB-MAN-03: Consumo y Procesamiento Idempotente en Inbox

#### Paso 3.1 — Insertar un Evento en el Inbox vía Base de Datos o API
```powershell
$eventId = [System.Guid]::NewGuid().ToString()
$tenantId = "11111111-1111-1111-1111-111111111111"

# Insertar simulación de evento recibido
docker compose exec mysql mysql -uintegration -pintegration integration -e "
INSERT INTO integration_inbox (event_id, tenant_id, event_type, payload, status, attempts, received_at)
VALUES (UUID_TO_BIN('$eventId'), UUID_TO_BIN('$tenantId'), 'ExternalOrderCreated', '{\"orderId\":\"ORD-1001\"}', 'RECEIVED', 0, NOW(6));
"

# Consultar estado inicial
docker compose exec mysql mysql -uintegration -pintegration integration -e "
SELECT BIN_TO_UUID(event_id) AS event_id, event_type, status, attempts, received_at, processed_at 
FROM integration_inbox 
WHERE event_id = UUID_TO_BIN('$eventId');
"
```
*Resultado Esperado:* Registro en `status = 'RECEIVED'`.

---

### Caso OIB-MAN-04: Deduplicación Idempotente ante Mensaje Repetido

#### Paso 4.1 — Simular Marcado a PROCESSED y Reenvío de Duplicado
```powershell
# 1. Simular que el evento fue procesado con éxito
docker compose exec mysql mysql -uintegration -pintegration integration -e "
UPDATE integration_inbox SET status = 'PROCESSED', processed_at = NOW(6) WHERE event_id = UUID_TO_BIN('$eventId');
"

# 2. Intentar registrar de nuevo el mismo eventId (como haría el InboxStore)
docker compose exec mysql mysql -uintegration -pintegration integration -e "
SELECT BIN_TO_UUID(event_id) AS event_id, status, processed_at 
FROM integration_inbox 
WHERE event_id = UUID_TO_BIN('$eventId') AND tenant_id = UUID_TO_BIN('$tenantId');
"
```
*Resultado Esperado:* El registro conserva `status = 'PROCESSED'`. El adaptador `InboxPersistenceAdapter` detecta la existencia previa y no ejecuta mutaciones de negocio secundarias.

---

### Caso OIB-MAN-05: Falla de Procesamiento y Enrutamiento a DLQ

#### Paso 5.1 — Simular Falla y Registro en DLQ
```powershell
$failedEventId = [System.Guid]::NewGuid().ToString()

# Insertar evento en DEAD_LETTER con mensaje de error
docker compose exec mysql mysql -uintegration -pintegration integration -e "
INSERT INTO integration_inbox (event_id, tenant_id, event_type, payload, status, attempts, last_error, received_at, processed_at)
VALUES (UUID_TO_BIN('$failedEventId'), UUID_TO_BIN('$tenantId'), 'CorruptedPayloadEvent', '{\"bad_json\":true}', 'DEAD_LETTER', 3, 'Fatal: Schema validation rejected payload', NOW(6), NULL);
"

# Verificar registro del error
docker compose exec mysql mysql -uintegration -pintegration integration -e "
SELECT BIN_TO_UUID(event_id) AS event_id, status, attempts, last_error 
FROM integration_inbox 
WHERE event_id = UUID_TO_BIN('$failedEventId');
"
```
*Resultado Esperado:* `status = 'DEAD_LETTER'`, `attempts = 3`, y `last_error` contiene la causa del fallo.

---

### Caso OIB-MAN-06: Verificación de Reintentos de Outbox y Backoff Exponencial

#### Paso 6.1 — Crear un Evento con Error de Publicación Simulado
```powershell
$retryEventId = [System.Guid]::NewGuid().ToString()

docker compose exec mysql mysql -uintegration -pintegration integration -e "
INSERT INTO integration_outbox (id, tenant_id, aggregate_type, aggregate_id, event_type, topic, payload, status, attempts, available_at, last_error, created_at)
VALUES (UUID_TO_BIN('$retryEventId'), UUID_TO_BIN('$tenantId'), 'Vehicle', UUID_TO_BIN(UUID()), 'vehicle.created', 'invalid.unreachable.topic', '{\"vin\":\"RETRY-001\"}', 'PENDING', 1, DATE_ADD(NOW(6), INTERVAL 10 SECOND), 'Kafka timeout connection', NOW(6));
"

# Comprobar que available_at está en el futuro (backoff activo)
docker compose exec mysql mysql -uintegration -pintegration integration -e "
SELECT BIN_TO_UUID(id) AS id, status, attempts, available_at, last_error 
FROM integration_outbox 
WHERE id = UUID_TO_BIN('$retryEventId');
"
```
*Resultado Esperado:* `status = 'PENDING'`, `attempts = 1`, `available_at` postergado 10 segundos en el futuro, impidiendo el reprocesamiento inmediato hasta que expire el backoff.

---

## 5. Registro de Ejecución de Pruebas Manuales

| Caso de Prueba | Resultado (PASS / FAIL) | Fecha de Ejecución | Ejecutor | Observaciones / Evidencia |
|---|---|---|---|---|
| **OIB-PRE-01** | | | | Validar migración Flyway V4 y tablas |
| **OIB-MAN-01** | | | | Inserción atómica `Vehicle` + Outbox `PENDING` |
| **OIB-MAN-02** | | | | Relay de Outbox a Kafka (`PUBLISHED` y `published_at`) |
| **OIB-MAN-03** | | | | Consumo y registro en Inbox (`RECEIVED` ➔ `PROCESSED`) |
| **OIB-MAN-04** | | | | Deduplicación de mensaje duplicado |
| **OIB-MAN-05** | | | | Enrutamiento a DLQ y estado `DEAD_LETTER` |
| **OIB-MAN-06** | | | | Reintentos con Backoff Exponencial en Outbox |
