# Guía de Configuración: IntegrationProfile para Adaptador de Base de Datos (JDBC)

Este documento describe la especificación completa, propósito de uso, validaciones de seguridad, estructura de metadatos y ejemplos prácticos para configurar un `IntegrationProfile` basado en el protocolo **`JDBC`** tanto para escenarios **`INBOUND`** como **`OUTBOUND`**.

---

## 1. Propósito de Uso

El adaptador de base de datos (`GenericJdbcAdapter` / `SapHanaExtractorAdapter`) permite conectar la plataforma con motores de bases de datos relacionales externos (MySQL, PostgreSQL, Oracle, Microsoft SQL Server, SAP HANA) sin requerir desarrollos de código a medida.

### Casos de Uso:
1. **INBOUND (Ingesta Incremental por Lotes / CDC liviano)**:
   - Extraer periódicamente datos nuevos o actualizados desde sistemas legacy (ej. ERPs como SIGO, SAP HANA, CRM on-premise).
   - Convertir cada registro extraído en un evento canónico estructurado mediante `JSLT` o `FIELD_MAPPING`.
   - Publicar los eventos resultantes en el bus Apache Kafka bajo el tópico correspondiente (`integration.<domain>.events`) garantizando persistencia en el `integration_outbox`.
2. **OUTBOUND (Inserción / Actualización de Datos en Base de Datos Externa)**:
   - Consumir eventos del bus Kafka y transformar su contenido para persistir registros en bases de datos externas de auditoría, réplicas analíticas o sistemas receptores basados en tablas relacionales.

---

## 2. Guardrail de Seguridad SQL (`SqlSecurityValidator`)

Para proteger tanto el sistema integrador como la base de datos externa, toda consulta SQL configurada en `extractionConfig.query` es analizada sintácticamente mediante **JSqlParser** antes de su ejecución:
- **Solo Consultas `SELECT`**: Se rechaza tajantemente cualquier sentencia que contenga comandos DML o DDL (`INSERT`, `UPDATE`, `DELETE`, `DROP`, `ALTER`, `TRUNCATE`, `EXEC`).
- **Prohibición de Multi-Statements**: Las consultas no pueden contener punto y coma (`;`) para evitar concatenación de consultas maliciosas.
- **Protección de Catálogos de Sistema**: Se bloquea el acceso a esquemas y tablas administrativas (`information_schema`, `sys`, `mysql`, `performance_schema`, etc.).
- **Parámetro Watermark Obligatorio**: La consulta debe contener de forma explícita el parámetro con nombre para el filtro incremental (ej. `:lastSyncWithBuffer`).

---

## 3. Estructura de Configuración de un Perfil JDBC

A continuación se detalla la anatomía de los campos requeridos en la creación de un `IntegrationProfile`:

| Campo | Tipo | Obligatorio | Descripción |
|---|---|---|---|
| `businessDomain` | String | Sí | Dominio de negocio asociado (ej. `units`, `customers`, `orders`, `billing`). |
| `externalSource` | String | Sí | Identificador del sistema de origen o destino (ej. `sigo-erp`, `sap-hana`). |
| `syncDirection` | Enum | Sí | Sentido de la integración: `INBOUND`, `OUTBOUND` o `BIDIRECTIONAL`. |
| `sourceOfTruth` | Enum | Sí | Fuente de la verdad: `SOURCE` o `PLATFORM`. |
| `protocol` | Enum | Sí | Debe ser `JDBC`. |
| `connector` | String | Sí | Nombre lógico para métricas y Circuit Breaker (ej. `sigo-mysql-connector`, `sap-hana-db`). |
| `adapter` | String | Sí | Nombre del adaptador: `generic-jdbc-adapter` o `sap-hana-adapter`. |
| `endpoint` | String | Sí | JDBC URL de conexión (ej. `jdbc:mysql://192.168.1.180:3306/sigo_db?useSSL=false`). |
| `credentialRef` | String | Sí | Ruta del secreto en Vault con usuario y contraseña (ej. `secret/sigo/db-credentials`). |
| `extractionConfig` | JSON Object | Sí (Inbound) | Parámetros de consulta, columna watermark, clave primaria y tamaño de lote. |
| `transformation` | JSON Object | Opcional | Script `JSLT` o reglas de mapeo para transformar el registro en evento canónico. |
| `syncPolicy` | JSON Object | Sí (Inbound) | Expresión CRON para el scheduler automático de extracción. |
| `retryPolicy` | JSON Object | Opcional | Política de reintentos (`maxAttempts`, `backoffMs`). |
| `rateLimitPolicy` | JSON Object | Opcional | Límite de ejecuciones concurrentes / peticiones por segundo. |

### Detalle de `extractionConfig` para JDBC:
```json
{
  "query": "SELECT id, placa AS numero_placa, motor AS numero_motor, marca, modelo, anio, fecha_actualizacion FROM t_unidades WHERE fecha_actualizacion > :lastSyncWithBuffer ORDER BY fecha_actualizacion ASC",
  "watermarkParam": "lastSyncWithBuffer",
  "watermarkColumn": "fecha_actualizacion",
  "keyColumn": "id",
  "fetchSize": 500
}
```

---

## 4. Configuración del Secreto en HashiCorp Vault

El perfil JDBC resuelve sus credenciales de base de datos desde Vault a través de `credentialRef`.

* **Ruta en Vault**: `secret/data/sigo/db-credentials` (o `secret/sigo/db-credentials`)
* **Comando para Registrar el Secreto en Vault**:
```bash
docker compose exec vault vault kv put secret/sigo/db-credentials \
  username="db_user_sigo" \
  password="TuPasswordSeguro123"
```

---

## 5. Ejemplos Prácticos de Configuración

### 5.1. Ejemplo INBOUND: Ingesta de Unidades/Vehículos desde Base de Datos MySQL (SIGO)

* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/integration-profiles`
* **Headers**:
  ```http
  Authorization: Bearer <TOKEN_JWT_ADMIN>
  X-Tenant-ID: 11111111-1111-1111-1111-111111111113
  Content-Type: application/json
  ```

#### Payload JSON:
```json
{
  "businessDomain": "units",
  "externalSource": "sigo-mysql",
  "syncDirection": "INBOUND",
  "sourceOfTruth": "SOURCE",
  "protocol": "JDBC",
  "connector": "sigo-mysql-connector",
  "adapter": "generic-jdbc-adapter",
  "endpoint": "jdbc:mysql://192.168.1.180:3306/sigo_db?useSSL=false&serverTimezone=UTC",
  "credentialRef": "secret/sigo/db-credentials",
  "extractionConfig": {
    "query": "SELECT id, placa AS numero_placa, motor AS numero_motor, cod_marca, modelo, anio, fecha_modificacion FROM unidades_vehiculos WHERE fecha_modificacion > :lastSyncWithBuffer ORDER BY fecha_modificacion ASC",
    "watermarkParam": "lastSyncWithBuffer",
    "watermarkColumn": "fecha_modificacion",
    "keyColumn": "id",
    "fetchSize": 500
  },
  "transformation": {
    "engine": "JSLT",
    "script": "{\n  \"externo_id\": string(.id),\n  \"numero_placa\": .numero_placa,\n  \"numero_motor\": .numero_motor,\n  \"marca\": lookup(\"BRAND_CODES\", .cod_marca, \"TOYOTA\"),\n  \"modelo\": (if (.modelo) .modelo else \"DESCONOCIDO\"),\n  \"anio\": (if (.anio) number(.anio) else 2026)\n}"
  },
  "syncPolicy": {
    "cronExpression": "0 */5 * * * *"
  },
  "retryPolicy": {
    "maxAttempts": 3,
    "backoffMs": 2000
  },
  "rateLimitPolicy": {
    "requestsPerSecond": 10
  }
}
```

---

### 5.2. Ejemplo OUTBOUND: Registro de Eventos en Réplica/Auditoría Externa

* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/integration-profiles`
* **Headers**:
  ```http
  Authorization: Bearer <TOKEN_JWT_ADMIN>
  X-Tenant-ID: 11111111-1111-1111-1111-111111111113
  Content-Type: application/json
  ```

#### Payload JSON:
```json
{
  "businessDomain": "orders",
  "externalSource": "analytics-db",
  "syncDirection": "OUTBOUND",
  "sourceOfTruth": "PLATFORM",
  "protocol": "JDBC",
  "connector": "analytics-mysql-connector",
  "adapter": "generic-jdbc-adapter",
  "endpoint": "jdbc:mysql://analytics.internal:3306/dw_integrations?useSSL=false&serverTimezone=UTC",
  "credentialRef": "secret/analytics/db-credentials",
  "transformation": {
    "engine": "JSLT",
    "script": "{\n  \"order_id\": .orderId,\n  \"customer_code\": .customerCode,\n  \"total_amount\": .totalAmount,\n  \"status\": .orderStatus,\n  \"synced_at\": now()\n}"
  },
  "retryPolicy": {
    "maxAttempts": 5,
    "backoffMs": 1000
  },
  "rateLimitPolicy": {
    "requestsPerSecond": 50
  }
}
```

---

## 6. Operación y Disparo Manual

Para forzar la ejecución inmediata de una sincronización Inbound sin esperar al scheduler CRON:

* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/integration-profiles/{profileId}/sync`
* **Headers**:
  ```http
  Authorization: Bearer <TOKEN_JWT_ADMIN>
  X-Tenant-ID: 11111111-1111-1111-1111-111111111113
  ```
* **Respuesta Exitosa (`HTTP 200 OK`)**:
  ```json
  {
    "profileId": "1449bc5f-205c-4937-9fa1-f75e96ad89a0",
    "status": "DISPATCHED",
    "dispatchedAt": "2026-08-21T20:35:00Z"
  }
  ```
