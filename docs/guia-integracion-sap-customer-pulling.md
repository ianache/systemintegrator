# Guía de Integración: SAP como Source of Truth (SoT) - Customer Pulling

## 1. Resumen Ejecutivo y Arquitectura

Esta guía define el estándar técnico para la integración del **System Integrator** con **SAP** actuando como **Source of Truth (SoT)** para la entidad **Customer**. 

El mecanismo de sincronización se basa en un modelo de **Pulling/Polling programado y delta incremental (High-Watermark)**, soportando dos estrategias de acceso a los datos en SAP según la infraestructura y capacidades del cliente:

1. **Estrategia Directa (BD SAP HANA / Read-Only Replica):** Conexión vía JDBC / HANA Client para escenarios de alto rendimiento y consulta masiva en tablas de base de datos (`KNA1`, `BUT000`, etc.).
2. **Estrategia Servicio (OData API / SAP HANA XS Services):** Invocación HTTP/REST de servicios OData v2/v4 o artefactos SAP HANA XS Engine (`.xsodata`), recomendada para mantener la lógica de capa de aplicación e integración estándar.

### Flujo End-to-End de la Sincronización

```text
+-------------------+      1. Cron Trigger (ShedLock)      +-------------------+
|  System Integrator| -----------------------------------> | Integrator Scheduler|
|  (Spring Boot)    |                                      +-------------------+
+-------------------+                                                |
          |                                                          | 2. Obtiene `lastSyncAt` &
          |                                                          |    CredentialRef
          v                                                          v
+----------------------------------------------------------------------------------+
|                          Estrategia de Acceso a SAP                              |
|  +-------------------------------------+    +---------------------------------+  |
|  | Opción 1: BD SAP HANA (JDBC/SQL)     |    | Opción 2: OData / SAP HANA XS  |  |
|  | SELECT ... WHERE ERDAT/AEDAT >= ... |    | GET /Customers?$filter=...      |  |
|  +-------------------------------------+    +---------------------------------+  |
+----------------------------------------------------------------------------------+
                                         |
                                         | 3. Responde registros delta de Customers
                                         v
+----------------------------------------------------------------------------------+
|                            Procesamiento Integrador                              |
|  1. Mapeo a DTO Canónico `Customer`                                             |
|  2. Validación de Idempotencia mediante Inbox MySQL                              |
|  3. Escritura Transaccional de Eventos en Outbox MySQL                           |
|  4. Actualización del Watermark `lastSyncAt` en IntegrationProfile               |
+----------------------------------------------------------------------------------+
                                         |
                                         | 4. Publicación Asíncrona Outbox Publisher
                                         v
                               +-------------------+
                               |  Kafka Cluster    |
                               |  customer.events  |
                               +-------------------+
```

---

## 2. Configuración Declarativa en `IntegrationProfile`

Toda la configuración del proceso de polling se almacena de forma aislada por cada inquilino (**Tenant**) en la entidad `IntegrationProfile`.

### 2.1 Modelo JSON de Configuración de Perfil (Ejemplo Opción 1: SAP HANA JDBC)

```json
{
  "businessDomain": "customers",
  "externalSource": "sap",
  "syncDirection": "INBOUND",
  "sourceOfTruth": "EXTERNAL",
  "protocol": "JDBC",
  "connector": "sap-hana-db",
  "adapter": "sap-hana-customer-jdbc-adapter",
  "endpoint": "jdbc:sap://hana-server.internal:30015?databaseName=S4H",
  "credentialRef": "secret/sap/hana-readonly-user",
  "cronExpression": "0 */15 * * * *",
  "pollingIntervalMs": 900000,
  "state": {
    "lastSyncAt": "2026-08-16T20:00:00Z",
    "overlapBufferSeconds": 300
  },
  "mapping": {
    "customerId": "KUNNR",
    "taxId": "STCD1",
    "legalName": "NAME1",
    "tradeName": "NAME2",
    "email": "SMTP_ADDR",
    "phone": "TEL_NUMBER",
    "addressStreet": "STRAS",
    "countryCode": "LAND1",
    "updatedAt": "AEDAT_TIMESTAMPS"
  },
  "retryPolicy": {
    "maxAttempts": 3,
    "initialBackoffMs": 1000
  }
}
```

### 2.2 Modelo JSON de Configuración de Perfil (Ejemplo Opción 2: SAP OData / XS)

```json
{
  "businessDomain": "customers",
  "externalSource": "sap",
  "syncDirection": "INBOUND",
  "sourceOfTruth": "EXTERNAL",
  "protocol": "REST_ODATA",
  "connector": "sap-odata-xs",
  "adapter": "sap-customer-odata-adapter",
  "endpoint": "https://sap-gateway.company.com/sap/opu/odata/sap/API_BUSINESS_PARTNER",
  "credentialRef": "secret/sap/odata-service-user",
  "cronExpression": "0 */10 * * * *",
  "pollingIntervalMs": 600000,
  "state": {
    "lastSyncAt": "2026-08-16T20:00:00Z",
    "overlapBufferSeconds": 120
  },
  "mapping": {
    "customerId": "Customer",
    "taxId": "BPTaxNumber",
    "legalName": "OrganizationBPName1",
    "tradeName": "OrganizationBPName2",
    "email": "EmailAddress",
    "phone": "PhoneNumber",
    "addressStreet": "StreetName",
    "countryCode": "Country",
    "updatedAt": "LastChangeDateTime"
  },
  "retryPolicy": {
    "maxAttempts": 3,
    "initialBackoffMs": 500
  }
}
```

---

## 3. Estrategias de Acceso a Datos SAP

### Estrategia 1: Vía BD SAP HANA (Acceso SQL Directo)

Recomendada cuando se requiere alta velocidad de lectura o no se dispone de licencias/servicios OData expuestos. Se utiliza un usuario de base de datos con permisos exclusivamente de lectura (`SELECT`) sobre las tablas o vistas analíticas de SAP.

#### Tablas Clave de SAP HANA:
- `KNA1`: Maestro de Clientes (General Data).
- `KNB1`: Datos de Cliente por Sociedad.
- `BUT000`: Business Partner Header (S/4HANA BP Model).
- `ADR6`: Direcciones de correo electrónico.

#### Consulta Delta Incremental (High-Watermark SQL):
```sql
SELECT 
    k.KUNNR AS customerId,
    k.STCD1 AS taxId,
    k.NAME1 AS legalName,
    k.NAME2 AS tradeName,
    k.STRAS AS addressStreet,
    k.LAND1 AS countryCode,
    a.SMTP_ADDR AS email,
    GREATEST(
        COALESCE(k.AEDAT, k.ERDAT), 
        COALESCE(b.AEDAT, '1970-01-01')
    ) AS lastChangeDate
FROM KNA1 k
LEFT JOIN KNB1 b ON k.KUNNR = b.KUNNR
LEFT JOIN ADR6 a ON k.ADRNR = a.ADDRNUMBER
WHERE 
    k.AEDAT >= :lastSyncWithBuffer 
    OR k.ERDAT >= :lastSyncWithBuffer
ORDER BY lastChangeDate ASC;
```

> **Nota de Seguridad:** Nunca realizar modificaciones (`UPDATE`/`DELETE`/`INSERT`) directamente sobre tablas estándar de SAP HANA.

---

### Estrategia 2: Vía OData / SAP HANA XS Services

Recomendada en arquitecturas modernas (SAP S/4HANA u S/4HANA Cloud / SAP BTP) y servicios creados con XS Engine (`.xsodata` / Classic o Advanced Engine).

#### 1. Endpoint Estándar S/4HANA OData (Business Partner API):
```text
GET /sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner
  ?$filter=LastChangeDateTime ge datetimeoffset'2026-08-16T20:00:00Z'
  &$expand=to_BusinessPartnerAddress,to_BusinessPartnerTax
  &$select=BusinessPartner,Customer,OrganizationBPName1,LastChangeDateTime,to_BusinessPartnerAddress/StreetName
  &$top=100
  &$inlinecount=allpages
```

#### 2. Endpoint Personalizado SAP HANA XS (`.xsodata`):
```text
GET /services/customers.xsodata/CustomerView
  ?$filter=UpdatedTimestamp ge datetime'2026-08-16T20:00:00Z'
  &$format=json
  &$top=500
```

---

## 4. Estrategia de Pulling Delta y Control de Consistencia (High-Watermark)

### Algoritmo de Sincronización Incremental

Para prevenir la pérdida de registros debido a transacciones abiertas en SAP o desalineación de relojes (*clock skew*), el System Integrator aplica un **Overlap Buffer (Traslape de Seguridad)** sobre la fecha del último procesamiento.

1. **Cálculo de la Ventana de Consulta:**
   $$\text{queryStartTime} = \text{lastSyncAt} - \text{overlapBufferSeconds}$$
2. **Ejecución del Polling:** Se invocan los datos de SAP usando `queryStartTime`.
3. **Control de Idempotencia:** Como el *overlap buffer* devolverá registros ya procesados anteriormente, cada registro extraído evalúa su hash/identificador en la tabla **Inbox** del System Integrator. Si el evento ya fue procesado, se descarta silenciosamente.
4. **Actualización de Watermark:** Una vez completada la transacción del lote, el `lastSyncAt` en la BD de perfiles se actualiza al valor del registro más reciente extraído.

---

## 5. Mapeo Canónico y Procesamiento Transaccional (Inbox / Outbox)

### 5.1 Estructura del Evento Canónico `Customer` (Kafka)

Los datos transformados se emiten bajo la estructura canónica del dominio:

```json
{
  "eventId": "evt_9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "eventType": "customer.updated",
  "eventTimestamp": "2026-08-16T22:30:00Z",
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

### 5.2 Garantía Transaccional local en MySQL (Outbox Pattern)

Para garantizar la consistencia en el procesamiento de clientes extraídos desde SAP:

```sql
START TRANSACTION;

-- 1. Verificar idempotencia en Inbox
INSERT INTO integration_inbox (message_id, tenant_id, source_system, processed_at)
VALUES ('SAP_CUST_CLI-0009841_20260816223000', '11111111-1111-1111-1111-111111111111', 'SAP', NOW());

-- 2. Registrar evento de dominio en Outbox
INSERT INTO integration_outbox (event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status, created_at)
VALUES (
    'evt_9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d',
    '11111111-1111-1111-1111-111111111111',
    'Customer',
    'CLI-0009841',
    'customer.updated',
    '{"customerId":"CLI-0009841", ...}',
    'PENDING',
    NOW()
);

-- 3. Actualizar Watermark de sincronización en el Perfil
UPDATE integration_profile 
SET state_json = JSON_SET(state_json, '$.lastSyncAt', '2026-08-16T22:30:00Z')
WHERE tenant_id = '11111111-1111-1111-1111-111111111111' AND business_domain = 'customers';

COMMIT;
```

---

## 6. Procesamiento Downstream Post-Kafka (Consumo e Integración)

Una vez que el evento canónico `customer.updated` o `customer.created` es publicado exitosamente en el tópico de Kafka (`customer.events`), el flujo de integración continúa hacia los **Sistemas Consumidores / Suscriptores** (CRM, Facturación, TMS, WMS, etc.).

```text
+-------------------+        Event publicado        +--------------------+
|  Kafka Cluster    | ----------------------------> | Kafka Consumer     |
|  customer.events  |                               | (Downstream App)   |
+-------------------+                               +--------------------+
                                                               |
                                                               | 1. Parse Event &
                                                               |    Read Tenant-ID
                                                               v
                                                    +--------------------+
                                                    | Consumer Inbox DB  |
                                                    | (Check EventId)    |
                                                    +--------------------+
                                                               |
                                                               | 2. If new event:
                                                               v
                                                    +--------------------+
                                                    | Sincronizar Datos  |
                                                    | en Sistema Destino |
                                                    +--------------------+
```

### 6.1 Suscripción y Ruteo por Tenant (`tenantId`)

1. **Particionado de Kafka:** Los eventos en el tópico `customer.events` utilizan el `tenantId` o `customerId` como **Kafka Message Key** (`record.key = tenantId:customerId`). Esto garantiza que todas las actualizaciones de un mismo cliente se procesen en **estricto orden de llegada**.
2. **Filtrado Multitenant:** Los consumidores deben validar el encabezado del registro o el atributo `tenantId` dentro del payload JSON para asegurarse de rutear los datos hacia el schema/base de datos adecuada del inquilino.

### 6.2 Idempotencia en el Consumidor (Consumer Inbox Pattern)

Para prevenir duplicados causados por rebalanceos de Kafka o reintentos de red (*At-Least-Once Delivery*):

```sql
-- Verificar y registrar el eventId antes de aplicar la actualización en el sistema destino
INSERT INTO consumer_inbox (event_id, tenant_id, source_system, processed_at)
VALUES ('evt_9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d', '11111111-1111-1111-1111-111111111111', 'SAP', NOW());
```

Si la inserción en `consumer_inbox` falla por clave duplicada (`PRIMARY KEY` sobre `event_id`), el consumidor confirma el offset (*ACK*) y descarta el procesamiento repetido.

### 6.3 Estrategia de Manejo de Errores en Consumidores (Dead Letter Topic - DLT)

Si un consumidor no puede procesar el evento (ej. base de datos destino no disponible o inconsistencia de regla de negocio):

1. **Retry Topic (Reintentos Cortos):** Se reintenta procesar el mensaje en un tópico secundario `customer.events.retry` con una política de pausa exponencial (ej. 3 reintentos).
2. **Dead Letter Topic (DLT):** Si los reintentos se agotan, el mensaje se envía automáticamente al tópico de falla `customer.events.dlt` preservando los headers originales y agregando el *Stacktrace* del error para su posterior audición y reprocesamiento manual.

---

## 7. Resiliencia, Reintentos y Manejo de Errores en Origen (SAP)

### 7.1 Política de Reintentos (Exponential Backoff)
Si la conexión a SAP HANA DB o al servicio OData/XS falla:
- **Intento 1:** Reintento inmediato (1 segundo de espera).
- **Intento 2:** Reintento tras 2 segundos.
- **Intento 3:** Reintento tras 4 segundos.

Si los 3 intentos fallan, la ejecución actual del job finaliza. El job **NO** actualiza el `lastSyncAt`, asegurando que el siguiente ciclo programado por el Cron vuelva a reintentar la extracción desde el mismo punto en el tiempo.

### 7.2 Manejo de Registros Corruptos o Incompletos (Dead Letter Table)
Si un registro específico devuelto por SAP carece de campos obligatorios para el modelo canónico (ej. `taxId` nulo):
- El registro individual se escribe en la tabla `integration_failed_records` con el payload de SAP y la causa del fallo.
- El proceso **no interrumpe** la importación del resto del lote válido.
- Se notifica una alerta para revisión funcional.

---

## 8. Ejecución Distribuida con ShedLock

Para asegurar que en un cluster de múltiples instancias del **System Integrator** solo un nodo ejecute el pulling por cada tenant a la vez, se aplica **ShedLock**:

```java
@Scheduled(cron = "${integration.sap.customer.pulling.cron:0 */10 * * * *}")
@SchedulerLock(
    name = "SAP_Customer_Pulling_Task", 
    lockAtMostFor = "14m", 
    lockAtLeastFor = "1m"
)
public void executeCustomerPullingTask() {
    // 1. Carga perfiles activos con externalSource = 'sap' y businessDomain = 'customers'
    // 2. Ejecuta la estrategia correspondiente (HANA DB o OData)
    // 3. Procesa registros e incrementa watermark
}
```

---

## 9. Verificación y Pruebas End-to-End

### Paso 1: Configurar Perfil mediante el API Gateway
```bash
curl -i -X POST http://localhost:8081/api/v1/integration-profiles \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{
    "businessDomain": "customers",
    "externalSource": "sap",
    "syncDirection": "INBOUND",
    "sourceOfTruth": "EXTERNAL",
    "protocol": "REST_ODATA",
    "connector": "sap-odata-xs",
    "adapter": "sap-customer-odata-adapter",
    "endpoint": "https://sap-gateway.company.com/sap/opu/odata/sap/API_BUSINESS_PARTNER",
    "credentialRef": "secret/sap/odata-service-user",
    "cronExpression": "0 */5 * * * *"
  }'
```

### Paso 2: Monitorear Publicación en Kafka
Consumir el tópico de eventos desde el contenedor de Kafka:
```bash
docker compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic customer.events \
  --from-beginning
```

