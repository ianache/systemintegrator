# Configurar una integración batch

Esta guía explica cómo configurar una integración que extrae registros desde una base de datos mediante JDBC, los divide en lotes y los envía a una API REST externa mediante un perfil `OUTBOUND`.

## 1. Flujo de procesamiento

```text
JDBC INBOUND
    │
    ├── lote de 50 registros ──┐
    ├── lote de 50 registros ──┼── Kafka ── OUTBOUND REST ── API externa
    └── lote restante ──────────┘
```

El tamaño se configura en el perfil `INBOUND`. Si la consulta devuelve 123 registros y `batchSize` es `50`, se generan tres eventos:

| Evento | Registros |
|---|---:|
| Lote 1 | 50 |
| Lote 2 | 50 |
| Lote 3 | 23 |

Cada lote es una unidad independiente para Kafka y para el envío HTTP.

## 2. Perfil JDBC INBOUND

El modo batch se activa dentro de `extractionConfig` con `batchMode: true` y `batchSize: 50`.

```json
{
  "businessDomain": "units",
  "externalSource": "sigo",
  "syncDirection": "INBOUND",
  "sourceOfTruth": "EXTERNAL",
  "protocol": "JDBC",
  "connector": "mysql-jdbc",
  "adapter": "mysql-jdbc-adapter",
  "endpoint": "jdbc:mysql://192.168.1.180:3307",
  "credentialRef": "secret/sigo/db-credentials",
  "extractionConfig": {
    "query": "SELECT * FROM (SELECT numero_motor, numero_placa, anio, color, GREATEST(creacion, COALESCE(modificacion, creacion)) AS CreateUpdate FROM vehiculos.tb_vehiculo WHERE creacion >= :last_date OR modificacion >= :last_date) TV ORDER BY CreateUpdate ASC",
    "watermarkParam": "last_date",
    "watermarkColumn": "CreateUpdate",
    "keyColumn": "numero_motor",
    "fetchSize": 1000,
    "batchMode": true,
    "batchSize": 50
  },
  "mapping": {
    "motor": "numero_motor",
    "placa": "numero_placa",
    "anio": "anio",
    "color": "color",
    "lastChangeDate": "CreateUpdate"
  },
  "syncPolicy": {
    "cronExpression": "0 */10 * * * *",
    "overlapBufferSeconds": 300
  }
}
```

### Diferencia entre `fetchSize` y `batchSize`

- `fetchSize` controla cuántas filas solicita el driver JDBC en cada lectura.
- `batchSize` controla cuántos registros contiene cada evento publicado.

Por eso `fetchSize: 1000` y `batchSize: 50` pueden coexistir.

## 3. Eventos generados

Para el dominio `units`, cada lote se guarda y publica con:

- Tipo de evento: `units.batch.upserted`
- Topic Kafka: `integration.units.batch.events`
- Payload: arreglo JSON no vacío

Ejemplo de payload de un lote:

```json
[
  {
    "motor": "MTR-001",
    "placa": "ABC-123",
    "anio": 2024,
    "color": "ROJO",
    "lastChangeDate": "2026-08-30T12:00:00Z"
  },
  {
    "motor": "MTR-002",
    "placa": "DEF-456",
    "anio": 2023,
    "color": "AZUL",
    "lastChangeDate": "2026-08-30T12:01:00Z"
  }
]
```

El publicador Kafka agrega estos headers:

```text
X-Tenant-ID: <tenant UUID>
X-Event-Type: units.batch.upserted
X-Business-Domain: units
X-External-Source: sigo
X-Batch-Mode: true
X-Batch-Size: 50
```

El último lote puede tener menos de 50 registros; `X-Batch-Size` representa el tamaño real de ese lote.

## 4. Perfil OUTBOUND REST

El perfil `OUTBOUND` no necesita `extractionConfig`, porque no extrae información. Consume los eventos de Kafka y realiza un `POST` al endpoint REST configurado.

```json
{
  "businessDomain": "units",
  "externalSource": "sigo-rest-api",
  "syncDirection": "OUTBOUND",
  "sourceOfTruth": "PLATFORM",
  "protocol": "REST",
  "connector": "sigo-units-bulk-api",
  "adapter": "generic-rest-adapter",
  "endpoint": "https://api.sigo.com/api/v1/units/bulk",
  "credentialRef": "secret/sigo/api-credentials",
  "retryPolicy": {
    "maxAttempts": 3,
    "backoffMs": 1000
  },
  "rateLimitPolicy": {
    "requestsPerSecond": 10
  }
}
```

El endpoint debe aceptar un arreglo JSON:

```http
POST https://api.sigo.com/api/v1/units/bulk
Content-Type: application/json
Authorization: Bearer <token>
```

```json
[
  {
    "motor": "MTR-001",
    "placa": "ABC-123",
    "anio": 2024,
    "color": "ROJO",
    "lastChangeDate": "2026-08-30T12:00:00Z"
  }
]
```

El sistema realiza un `POST` por cada evento batch. No vuelve a dividir el lote ni acumula varios eventos antes de llamar a la API.

## 5. Credenciales REST

`credentialRef` apunta al secreto que contiene la autenticación del endpoint. Por ejemplo, para OAuth2 Client Credentials:

```json
{
  "tokenUrl": "https://oauth2.qa.comsatel.com.pe/realms/microservicios/protocol/openid-connect/token",
  "clientId": "sigo-integration",
  "clientSecret": "<secret>",
  "scope": "openid"
}
```

También se soportan secretos con autenticación Basic, Bearer o API Key, según el tipo de credencial resuelto por Vault.

## 6. Cómo se relacionan ambos perfiles

El `OUTBOUND` se selecciona usando:

1. `businessDomain` del evento, en este caso `units`.
2. `syncDirection` igual a `OUTBOUND` o `BIDIRECTIONAL`.
3. `protocol` igual a `REST`.
4. Un perfil activo para el tenant del evento.

El `externalSource` del OUTBOUND debe ser diferente al `externalSource` del INBOUND. Esto evita que el mecanismo anti-loop descarte el evento como si regresara al mismo origen.

En este ejemplo:

```text
INBOUND externalSource  = sigo
OUTBOUND externalSource = sigo-rest-api
```

## 7. Transformación del payload batch

Los eventos cuyo tipo termina en `.batch.upserted` se envían como arreglos y se conserva su payload batch. El endpoint REST debe esperar la estructura final que se publicó en Kafka.

Si la API externa necesita un envoltorio, por ejemplo:

```json
{
  "items": [
    {
      "motor": "MTR-001"
    }
  ]
}
```

esa estructura debe producirse antes de publicar el evento batch o debe implementarse explícitamente una transformación batch. El perfil OUTBOUND estándar no vuelve a transformar el arreglo batch antes del envío.

## 8. Watermark e idempotencia

El INBOUND utiliza `watermarkColumn` para determinar el avance de la sincronización. Con `overlapBufferSeconds: 300`, cada ejecución vuelve a consultar una ventana de cinco minutos para evitar perder registros modificados durante la ejecución.

Los registros repetidos dentro de esa ventana se controlan mediante las claves de entrega derivadas de:

```text
tenantId + businessDomain + keyColumn
```

La consulta debe ordenar por la columna watermark (`CreateUpdate ASC`) para mantener un orden estable entre lotes.

## 9. Recomendaciones para la API destino

La API REST debe:

- Aceptar `POST` con un arreglo JSON.
- Permitir entre 1 y 50 elementos por request.
- Responder con un código `2xx` cuando procese correctamente el lote.
- Ser idempotente usando `motor` como clave de negocio.
- Rechazar o reportar claramente errores parciales.
- Soportar reintentos sin duplicar registros.

El reintento se realiza a nivel del evento batch completo. No existen ACK parciales por registro dentro del lote.

## 10. Verificación

Antes de activar la sincronización periódica:

1. Crear el perfil JDBC `INBOUND`.
2. Crear el perfil REST `OUTBOUND` para el mismo tenant y dominio.
3. Ejecutar una sincronización manual.
4. Verificar que el outbox contenga eventos `units.batch.upserted`.
5. Confirmar que Kafka publique en `integration.units.batch.events`.
6. Confirmar que la API REST reciba un `POST` por lote.
7. Revisar que el último lote tenga el tamaño restante esperado.
8. Repetir la ejecución y comprobar que no se creen duplicados.

Si no se recibe ninguna llamada HTTP, revisar primero que el perfil OUTBOUND esté activo, tenga `protocol: REST`, coincida el `businessDomain` y use un `externalSource` diferente al del INBOUND.
