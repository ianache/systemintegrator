# Guía de Integración: SAP HANA a Dominio Ventas (Customer Sync)

## 1. Resumen Ejecutivo y Arquitectura

Esta guía define la arquitectura e implementación paso a paso para extraer **Customers** creados o modificados en **SAP** vía consulta directa a **BD SAP HANA** cada hora y sincronizarlos de forma saliente (**Outbound / Push**) hacia los endpoints del microservicio del dominio de **Ventas**.

### Diagrama de Flujo de la Integración (Modelo Canónico Intermedio)

```text
+-------------------------+      1. Cron Trigger (Cada 1h)     +-------------------------+
| BD SAP HANA             | <--------------------------------- | System Integrator       |
| (KNA1, BUT000, ADR6)    |                                    | (Pulling Component)     |
+-------------------------+                                    +-------------------------+
             |                                                              |
             | 2. Retorna registros delta                                    | 3. Transforma a Evento
             |    (ERDAT/AEDAT >= lastSyncAt - buffer)                       |    Canónico `Customer`
             v                                                              v
+----------------------------------------------------------------------------------------+
|                      PASO A: Evento Canónico en Kafka (Bus Central)                    |
|                      Tópico: `customer.events` (Payload Canónico Agnóstico)            |
+----------------------------------------------------------------------------------------+
                                            |
                                            | 4. Adaptador/Consumidor del Dominio Ventas
                                            |    Suscrito a `customer.events`
                                            v
+----------------------------------------------------------------------------------------+
|                      PASO B: Transformación y Despacho a Ventas                        |
|  a) Recibe Evento Canónico `Customer`                                                  |
|  b) Transforma a DTO específico de Ventas (`VentasCustomerCreate/UpdateDTO`)           |
|  c) Guarda en Outbox local de Ventas                                                   |
+----------------------------------------------------------------------------------------+
                                            |
                                            | 5. Invoca HTTP Rest API Ventas
                                            v
                               +-------------------------+
                               | Microservicio Ventas    |
                               | (API HTTP Rest)         |
                               +-------------------------+
                                 /                     \
                   6a. Si es Cliente Nuevo             6b. Si Cliente ya existe
                           /                                 \
                          v                                   v
             POST /ventas/api/v1/customer           PUT /ventas/api/v1/customer/:id
```

---

## 2. Configuración Declarativa en `IntegrationProfile`

La extracción desde SAP HANA se configura en el `IntegrationProfile` del inquilino (**Tenant**). Observa que el `mapping` define la regla de transformación hacia el **Modelo Canónico de Integración**, y el `endpoint` indica el string de conexión JDBC a SAP HANA:

```json
{
  "businessDomain": "customers",
  "externalSource": "sap",
  "syncDirection": "INBOUND",
  "sourceOfTruth": "EXTERNAL",
  "protocol": "JDBC",
  "connector": "sap-hana-db",
  "adapter": "sap-hana-customer-adapter",
  "endpoint": "jdbc:sap://hana-server.internal:30015?databaseName=S4H",
  "credentialRef": "secret/sap/hana-readonly-user",
  "mapping": "{\"customerId\":\"KUNNR\",\"taxId\":\"STCD1\",\"legalName\":\"NAME1\",\"tradeName\":\"NAME2\",\"addressStreet\":\"STRAS\",\"countryCode\":\"LAND1\",\"email\":\"SMTP_ADDR\"}",
  "syncPolicy": "{\"cronExpression\":\"0 0 * * * *\",\"overlapBufferSeconds\":300}",
  "retryPolicy": "{\"maxAttempts\":3,\"initialBackoffMs\":1000}"
}
```

> **Alineación con el Dominio:** El `IntegrationProfile` no contiene un campo `targetEndpoint`. Su responsabilidad en la fase **INBOUND** se enfoca exclusivamente en definir la extracción de la fuente externa (`endpoint`) y su mapeo al modelo canónico (`mapping`).

---

## 3. Extracción Delta en BD SAP HANA (Pulling)

### Consulta SQL con Watermark Incremental

El componente de pulling consulta la base de datos SAP HANA filtrando por registros creados (`ERDAT`) o modificados (`AEDAT`) en la última hora (aplicando un buffer de seguridad de 5 minutos):

```sql
SELECT 
    k.KUNNR AS codigoCliente,
    k.STCD1 AS numDocumento,
    k.NAME1 AS razonSocial,
    k.NAME2 AS nombreComercial,
    k.STRAS AS direccionFiscal,
    k.LAND1 AS codigoPais,
    a.SMTP_ADDR AS correoContacto,
    t.TELF1 AS telefonoContacto,
    CASE 
        WHEN k.ERDAT >= :lastSyncWithBuffer AND k.AEDAT IS NULL THEN 'CREATE'
        ELSE 'UPDATE'
    END AS tipoOperacion,
    GREATEST(
        COALESCE(k.AEDAT, k.ERDAT), 
        COALESCE(b.AEDAT, '1970-01-01')
    ) AS fechaModificacion
FROM KNA1 k
LEFT JOIN KNB1 b ON k.KUNNR = b.KUNNR
LEFT JOIN ADR6 a ON k.ADRNR = a.ADDRNUMBER
LEFT JOIN ADR2 t ON k.ADRNR = t.ADDRNUMBER
WHERE 
    k.AEDAT >= :lastSyncWithBuffer 
    OR k.ERDAT >= :lastSyncWithBuffer
ORDER BY fechaModificacion ASC;
```

---

## 4. Arquitectura de Transformación: De SAP a Canónico y a Dominio Ventas

Para mantener el principio de desacoplamiento del PRD, **el conector de SAP no conoce la estructura del microservicio de Ventas ni sus endpoints HTTP**. La integración consta de 2 fases de transformación independientes:

---

### Fase 1: De Estructura SAP (Tablas KNA1/ADR6) a Evento Canónico `Customer`

El componente de pulling extrae los datos de SAP HANA y emite el **Evento Canónico de Integración** al bus Kafka (`customer.events`):

```json
{
  "eventId": "evt_9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "eventType": "customer.updated",
  "eventTimestamp": "2026-08-16T23:00:00Z",
  "tenantId": "11111111-1111-1111-1111-111111111111",
  "source": "SAP",
  "payload": {
    "customerId": "CLI-0009841",
    "taxId": "20100067891",
    "legalName": "EMPRESA DE PRUEBA S.A.C.",
    "tradeName": "EMPRESA PRUEBA",
    "contact": {
      "email": "contacto@empresaprueba.com",
      "phone": "+51999999999"
    },
    "address": {
      "street": "AV. PRINCIPAL 123",
      "countryCode": "PE"
    },
    "status": "ACTIVE"
  }
}
```

---

### Fase 2: Del Evento Canónico `Customer` a Payloads Específicos del Dominio Ventas

El **Adaptador del Dominio Ventas** escucha el tópico `customer.events`, consume el Evento Canónico y traduce los atributos según las reglas del contrato REST de Ventas:

#### Mapeo de Atributos:

| Evento Canónico `Customer` | DTO Creación POST (`/ventas/.../customer`) | DTO Edición PUT (`/ventas/.../customer/:id`) |
|---|---|---|
| `payload.customerId` | `codigoClienteSap` | Mapeado en la URL (`:id`) |
| `payload.taxId` | `numeroDocumento` | `numeroDocumento` |
| `payload.legalName` | `razonSocial` | `razonSocial` |
| `payload.tradeName` | `nombreComercial` | `nombreComercial` |
| `payload.contact.email` | `datosContacto.email` | `datosContacto.email` |
| `payload.contact.phone` | `datosContacto.telefono` | `datosContacto.telefono` |
| `payload.address.street` | `ubicacion.direccion` | `ubicacion.direccion` |
| `payload.address.countryCode` | `ubicacion.pais` | `ubicacion.pais` |
| `payload.status` | `estadoCliente` | `estadoCliente` |

---

### 4.1 Payload Generado para Creación (POST `/ventas/api/v1/customer`)

Cuando se detecta que el cliente no ha sido registrado previamente en Ventas:

```json
{
  "codigoClienteSap": "CLI-0009841",
  "numeroDocumento": "20100067891",
  "tipoDocumento": "RUC",
  "razonSocial": "EMPRESA DE PRUEBA S.A.C.",
  "nombreComercial": "EMPRESA PRUEBA",
  "datosContacto": {
    "email": "contacto@empresaprueba.com",
    "telefono": "+51999999999"
  },
  "ubicacion": {
    "direccion": "AV. PRINCIPAL 123",
    "pais": "PE"
  },
  "estadoCliente": "ACTIVO"
}
```

### 4.2 Payload Generado para Actualización (PUT `/ventas/api/v1/customer/:id`)

Cuando el cliente ya existe en Ventas (URL destino: `PUT /ventas/api/v1/customer/CLI-0009841`):

```json
{
  "numeroDocumento": "20100067891",
  "razonSocial": "EMPRESA DE PRUEBA S.A.C.",
  "nombreComercial": "EMPRESA PRUEBA MODIFICADA",
  "datosContacto": {
    "email": "nuevo_contacto@empresaprueba.com",
    "telefono": "+51999999999"
  },
  "ubicacion": {
    "direccion": "AV. PRINCIPAL 123 OTR",
    "pais": "PE"
  },
  "estadoCliente": "ACTIVO"
}
```

---

## 5. Estrategia de Envío HTTP Push y Manejo de Respuestas

El adaptador de salida ejecuta la llamada al API REST del microservicio de Ventas aplicando la siguiente lógica:

1. **Evaluación del Endpoint:**
   - Si el cliente es nuevo o no existe registro de sincronización previa en el System Integrator: realiza invocación **POST** a `/ventas/api/v1/customer`.
   - Si el cliente devuelve HTTP `409 Conflict` (ya existe en Ventas) o se detectó como actualización en SAP: realiza invocación **PUT** a `/ventas/api/v1/customer/:id` (donde `:id` corresponde a `codigoClienteSap`).

2. **Respuestas HTTP Esperadas:**
   - `201 Created` (para POST): Cliente registrado en Ventas.
   - `200 OK` / `204 No Content` (para PUT): Cliente actualizado en Ventas.
   - `400 Bad Request`: Error de validación de datos en el payload de Ventas (se envía a tabla de errores).
   - `5xx Server Error`: Falla temporal en el microservicio de Ventas (activa política de reintentos).

---

## 6. Manejo Transaccional, Idempotencia y Reintentos

### 6.1 Registro Transaccional local en MySQL (Outbox Pattern)

Cada registro obtenido de SAP HANA se almacena en la tabla Outbox del System Integrator antes de su invocación HTTP:

```sql
START TRANSACTION;

-- 1. Registrar en Inbox para evitar doble procesamiento desde SAP
INSERT INTO integration_inbox (message_id, tenant_id, source_system, processed_at)
VALUES ('SAP_HANA_CUST_CLI-0009841_20260816230000', '11111111-1111-1111-1111-111111111111', 'SAP_HANA', NOW());

-- 2. Registrar en Outbox para despacho hacia el API de Ventas
INSERT INTO integration_outbox (event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status, created_at)
VALUES (
    'evt_ventas_9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d',
    '11111111-1111-1111-1111-111111111111',
    'VentasCustomer',
    'CLI-0009841',
    'POST_VENTAS_CUSTOMER',
    '{"codigoClienteSap":"CLI-0009841", "numeroDocumento":"20100067891", ...}',
    'PENDING',
    NOW()
);

-- 3. Actualizar Watermark lastSyncAt
UPDATE integration_profile 
SET state_json = JSON_SET(state_json, '$.lastSyncAt', '2026-08-16T23:00:00Z')
WHERE tenant_id = '11111111-1111-1111-1111-111111111111' AND business_domain = 'ventas';

COMMIT;
```

### 6.2 Política de Reintentos HTTP (Outbox Publisher)

Si el llamado HTTP hacia el microservicio de Ventas falla por caídas de red o `503 Service Unavailable`:
- **Estrategia:** Reintentos con Exponential Backoff (1s, 2s, 4s).
- **Control de Fallas:** Si los reintentos fallan, el registro permanece en estado `FAILED_RETRYABLE` en la tabla Outbox para ser procesado en el siguiente ciclo por un worker secundario, sin bloquear el avance del Watermark en SAP HANA.

---

## 7. Ejecución Programada con ShedLock

Para garantizar que en un entorno distribuido solo una instancia del System Integrator ejecute la consulta a SAP HANA cada hora:

```java
@Scheduled(cron = "${integration.sap.ventas.customer.cron:0 0 * * * *}")
@SchedulerLock(
    name = "SAP_HANA_To_Ventas_Customer_Task", 
    lockAtMostFor = "55m", 
    lockAtLeastFor = "5m"
)
public void executeSapHanaToVentasSync() {
    // 1. Obtiene perfiles activos (externalSource='sap', businessDomain='ventas')
    // 2. Realiza query SQL a BD SAP HANA con filtro incremental lastSyncAt
    // 3. Evalúa si aplica POST o PUT según tipoOperacion / existencia
    // 4. Invoca API de Ventas y actualiza Outbox/Watermark
}
```
