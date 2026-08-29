# Guía de Configuración: Adaptador API REST / HTTP

Este documento resume el contrato soportado por el adaptador REST para perfiles `INBOUND` y `OUTBOUND`. En esta entrega se documenta con precisión el alcance del flujo inbound REST que sincroniza datos hacia `integration_outbox`.

---

## 1. Propósito

El protocolo `REST` permite:

- `OUTBOUND`: despachar eventos hacia APIs externas o microservicios Core.
- `INBOUND`: consultar endpoints HTTP externos, extraer registros JSON, transformarlos y persistirlos en `integration_outbox`.

El slice actual de inbound REST no incluye paginación.

---

## 2. Autenticación soportada (`credentialRef`)

El secreto referenciado por `credentialRef` puede resolver cualquiera de estos esquemas:

### 2.1. OAuth2 Client Credentials

```json
{
  "tokenUrl": "https://oauth2.qa.comsatel.com.pe/realms/microservicios/protocol/openid-connect/token",
  "clientId": "unidad",
  "clientSecret": "secret-value",
  "scope": "openid",
  "headers": {
    "x-audit": "424234234",
    "X-Distribuidor-Id": "1"
  }
}
```

### 2.2. API Key

```json
{
  "apiKey": "mi-super-api-key",
  "headers": {
    "X-Partner-Id": "comsatel-peru"
  }
}
```

### 2.3. Basic Authentication

```json
{
  "username": "api_user",
  "password": "api_password_123"
}
```

### 2.4. Bearer Token estático

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

---

## 3. Contrato del perfil REST

| Campo | Tipo | Obligatorio | Descripción |
|---|---|---|---|
| `businessDomain` | String | Sí | Dominio de negocio (`customers`, `units`, etc.). |
| `externalSource` | String | Sí | Identificador de la API o sistema externo. |
| `syncDirection` | Enum | Sí | `INBOUND`, `OUTBOUND` o `BIDIRECTIONAL`. |
| `sourceOfTruth` | Enum | Sí | `SOURCE` o `PLATFORM`. |
| `protocol` | Enum | Sí | Debe ser `REST`. |
| `connector` | String | Sí | Nombre lógico usado en métricas y resiliencia. |
| `adapter` | String | Sí | Nombre del adaptador REST configurado. |
| `endpoint` | String | Sí | Base URL del servicio HTTP remoto. En perfiles `OUTBOUND`, es también el endpoint bulk para eventos batch. |
| `credentialRef` | String | No | Ruta del secreto con autenticación y headers. |
| `transformation` | JSON Object | No | Transformación declarativa; para inbound se recomienda `JSLT`. |
| `extractionConfig` | JSON Object | Sí en inbound | Configura método HTTP, path, query params, JSONPath y llave de negocio. |

---

## 4. Alcance soportado para inbound REST

### 4.1. Métodos HTTP

`extractionConfig.method` soporta:

- `GET`
- `POST`
- `PUT`
- `PATCH`

Otros métodos no forman parte de este slice.

### 4.2. Sustitución de watermark

`extractionConfig.queryParams` puede incluir un placeholder `:<watermarkParam>` para sustituir el último watermark conocido en tiempo de ejecución.

Ejemplo:

```json
{
  "method": "GET",
  "path": "/api/v2/customers",
  "queryParams": {
    "updatedSince": ":updatedSince"
  },
  "watermarkParam": "updatedSince",
  "watermarkFormat": "ISO_8601",
  "responseJsonPath": "$.items[*]",
  "keyProperty": "id",
  "watermarkColumn": "updatedAt"
}
```

En el ejemplo anterior el adaptador reemplaza `:updatedSince` por el valor del watermark actual formateado como `ISO_8601`.

### 4.3. Formas de respuesta aceptadas

El adaptador soporta dos formas de respuesta JSON:

- Un arreglo de objetos resuelto por `responseJsonPath`, por ejemplo `$.items[*]`.
- Un objeto raíz cuando `responseJsonPath` es `$`.

No se soportan escalares ni estructuras donde `responseJsonPath` no resuelva objetos.

### 4.4. `keyProperty` requerido

Para perfiles REST inbound, `extractionConfig.keyProperty` es obligatorio. Ese campo se usa como llave de negocio del registro extraído antes de persistir el evento en `integration_outbox`.

Si una fila no contiene `keyProperty`, la sincronización falla.

### 4.5. Paginación

La paginación no está soportada en este slice de inbound REST. El adaptador procesa una sola respuesta HTTP por ejecución de sincronización.

---

## 5. Ejemplo inbound REST

```json
{
  "businessDomain": "customers",
  "externalSource": "crm-external-api",
  "syncDirection": "INBOUND",
  "sourceOfTruth": "SOURCE",
  "protocol": "REST",
  "connector": "crm-api-connector",
  "adapter": "generic-rest-adapter",
  "endpoint": "https://api.crm-partner.com",
  "credentialRef": "secret/crm/api-credentials",
  "transformation": {
    "engine": "JSLT",
    "script": "{\n  \"customerId\": .id,\n  \"legalName\": .name\n}"
  },
  "extractionConfig": {
    "method": "GET",
    "path": "/api/v2/customers",
    "queryParams": {
      "updatedSince": ":updatedSince"
    },
    "watermarkParam": "updatedSince",
    "watermarkFormat": "ISO_8601",
    "responseJsonPath": "$.items[*]",
    "keyProperty": "id",
    "watermarkColumn": "updatedAt"
  },
  "syncPolicy": {
    "cronExpression": "0 0 * * * *"
  }
}
```

---

## 6. Notas operativas

## 7. Extracción REST por lotes

El modo batch se activa en `extractionConfig` con `batchMode: true`. Sus valores por defecto son `batchMode=false` y `batchSize=500`; si `batchSize` es nulo, cero o negativo, se normaliza a `500`. El `responseJsonPath` debe seguir resolviendo un arreglo de objetos y `keyProperty` continúa siendo obligatorio para cada elemento.

Ejemplo conciso de perfil inbound REST con transformación JSLT orientada a arreglos:

```json
{
  "businessDomain": "customers",
  "protocol": "REST",
  "syncDirection": "INBOUND",
  "endpoint": "https://api.crm-partner.com",
  "transformation": {
    "engine": "JSLT",
    "script": "[ for (.) { \"customerId\": .id, \"legalName\": .name } ]"
  },
  "extractionConfig": {
    "method": "GET",
    "path": "/api/v2/customers",
    "responseJsonPath": "$.items[*]",
    "keyProperty": "id",
    "watermarkColumn": "updatedAt",
    "batchMode": true,
    "batchSize": 200
  }
}
```

Cada lote se transforma como un arreglo y genera un evento `<domain>.batch.upserted` en el tópico `integration.<domain>.batch.events`. Kafka propaga `X-Batch-Mode: true` y `X-Batch-Size` con el número de elementos del lote. Para esos eventos, el `endpoint` ya configurado en el perfil `OUTBOUND` es el destino bulk; no existe un campo `bulkEndpoint` separado.

El modo batch no habilita micro-batching ni buffering en el consumidor Kafka, y los ACK parciales por elemento quedan fuera de alcance. El mensaje batch se procesa y confirma como una sola unidad.

- Los headers configurados en el secreto se propagan a la petición HTTP.
- El adaptador registra errores sanitizados para no exponer credenciales o tokens en mensajes de fallo.
- El resultado transformado se persiste en `integration_outbox`; la publicación posterior a Kafka pertenece al flujo de relay, no al adaptador REST inbound.
