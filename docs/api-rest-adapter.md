# Guía de Configuración: IntegrationProfile para Adaptador API REST / HTTP

Este documento describe la especificación completa, propósito de uso, mecanismos de autenticación (OAuth2 / Keycloak, API-Key, Basic, Bearer), propagación de cabeceras personalizadas, filtrado anti-loop y ejemplos prácticos para configurar un `IntegrationProfile` basado en el protocolo **`REST`** tanto para **`INBOUND`** como para **`OUTBOUND`**.

---

## 1. Propósito de Uso

El adaptador REST (`HttpOutboundClient` / `GenericRestAdapter`) permite comunicar la plataforma de integración con servicios web, microservicios Core y APIs externas (JSON/HTTPS) de forma desacoplada y resiliente.

### Casos de Uso:
1. **OUTBOUND (Despacho de Eventos hacia APIs Externas o Microservicios Core)**:
   - Consumir eventos del bus Kafka (`integration.<domain>.events`).
   - Aplicar transformaciones complejas `JSLT` para transformar el formato canónico al esquema esperado por la API destino (incluyendo arreglos de atributos, objetos anidados y mapeos de catálogo con `lookup()`).
   - Obtener y cachear tokens OAuth2 de forma automática desde un servidor OIDC (Keycloak) mediante *Client Credentials*.
   - Inyectar cabeceras HTTP personalizadas de auditoría y contexto (ej. `x-audit`, `X-Distribuidor-Id`, `X-Tenant-ID`).
   - Ejecutar la llamada HTTP con protección de Circuit Breaker (`Resilience4j`) y registro sanitizado en logs (`SensitiveDataRedactor`).
2. **INBOUND (Polling de APIs REST Externas)**:
   - Consultar periódicamente endpoints HTTP externos usando filtros de fecha/watermark y paginación.
   - Extraer la lista de objetos usando `responseJsonPath` (ej. `$.data[*]`).
   - Transformar cada elemento y persistirlo en el `integration_outbox` para su posterior publicación a Kafka.

---

## 2. Tipos de Autenticación Soportados en Vault (`credentialRef`)

El perfil REST referencia un secreto en HashiCorp Vault. El sistema detecta automáticamente el tipo de autenticación según la estructura de claves presentes en el secreto:

### 2.1. OAuth2 Client Credentials (Keycloak / OIDC)
Obtiene y renueva automáticamente tokens JWT Bearer antes de su expiración:
```json
{
  "tokenUrl": "https://oauth2.qa.comsatel.com.pe/realms/microservicios/protocol/openid-connect/token",
  "clientId": "unidad",
  "clientSecret": "gGhOPdKqN20jrbAxPpwl0tPumRLcSIrK",
  "scope": "openid",
  "headers": {
    "x-audit": "424234234",
    "X-Distribuidor-Id": "1"
  }
}
```
*Comando Vault*:
```bash
docker compose exec vault vault kv put secret/cl2/comsatel-unidad-credentials \
  tokenUrl="https://oauth2.qa.comsatel.com.pe/realms/microservicios/protocol/openid-connect/token" \
  clientId="unidad" \
  clientSecret="gGhOPdKqN20jrbAxPpwl0tPumRLcSIrK" \
  scope="openid" \
  headers='{"x-audit":"424234234","X-Distribuidor-Id":"1"}'
```

### 2.2. API Key
Inyecta la cabecera `X-API-Key: <valor>`:
```json
{
  "apiKey": "mi-super-api-key-secreta-9999",
  "headers": {
    "X-Partner-Id": "comsatel-peru"
  }
}
```

### 2.3. HTTP Basic Authentication
Genera la cabecera `Authorization: Basic <base64(usuario:password)>`:
```json
{
  "username": "api_user",
  "password": "api_password_123"
}
```

### 2.4. Bearer Token Estático
Inyecta la cabecera `Authorization: Bearer <token>`:
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

---

## 3. Estructura de Configuración de un Perfil REST

| Campo | Tipo | Obligatorio | Descripción |
|---|---|---|---|
| `businessDomain` | String | Sí | Dominio de negocio (ej. `units`, `vehicles`, `brands`, `models`, `customers`). |
| `externalSource` | String | Sí | Identificador de la API externa (ej. `comsatel-unidad-api`, `cl2-core-api`). |
| `syncDirection` | Enum | Sí | `OUTBOUND`, `INBOUND` o `BIDIRECTIONAL`. |
| `sourceOfTruth` | Enum | Sí | `PLATFORM` o `SOURCE`. |
| `protocol` | Enum | Sí | Debe ser `REST`. |
| `connector` | String | Sí | Nombre lógico para métricas y Circuit Breaker (ej. `comsatel-unidad-rest`). |
| `adapter` | String | Sí | Nombre del adaptador: `generic-http-adapter` o `cl2-rest-adapter`. |
| `endpoint` | String | Sí | URL completa del endpoint HTTP (ej. `https://api.qa.comsatel.com.pe/unidad/api/v1/unidad`). |
| `credentialRef` | String | Opcional | Ruta al secreto en Vault con las credenciales y headers. |
| `transformation` | JSON Object | Opcional | Script `JSLT` declarativo para moldear el JSON enviado. |
| `extractionConfig` | JSON Object | Sí (Inbound) | Path, método HTTP, parámetros y `responseJsonPath` para extraer listas. |
| `retryPolicy` | JSON Object | Opcional | Política de reintentos (`maxAttempts`, `backoffMs`). |
| `rateLimitPolicy` | JSON Object | Opcional | Límite de peticiones por segundo (`requestsPerSecond`). |

---

## 4. Ejemplos Prácticos de Configuración

### 4.1. Ejemplo OUTBOUND: Despacho de Unidades a API Externa con Keycloak y Transformación JSLT Jerárquica

Este perfil toma eventos planos del dominio `units` y los transforma en la estructura jerárquica con arreglos de atributos (`atributosUnidad`) requerida por la API de Comsatel QA:

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
  "externalSource": "comsatel-unidad-api",
  "syncDirection": "OUTBOUND",
  "sourceOfTruth": "PLATFORM",
  "protocol": "REST",
  "connector": "comsatel-unidad-rest",
  "adapter": "generic-http-adapter",
  "endpoint": "https://api.qa.comsatel.com.pe/unidad/api/v1/unidad",
  "credentialRef": "secret/cl2/comsatel-unidad-credentials",
  "transformation": {
    "engine": "JSLT",
    "script": "{\n  \"tipoUnidadId\": 2,\n  \"externoId\": (if (.externo_id) .externo_id else if (.numero_motor) .numero_motor else \"0\"),\n  \"alias\": .numero_placa,\n  \"propietarioId\": (if (.propietario_id) .propietario_id else 45),\n  \"creadorUsuarioId\": (if (.usuario_id) .usuario_id else \"45ERT34F453534F\"),\n  \"fuenteId\": 1,\n  \"atributosUnidad\": [\n    {\n      \"atributoId\": 1,\n      \"valor\": .numero_placa\n    },\n    {\n      \"atributoId\": 2,\n      \"valor\": .numero_motor\n    },\n    {\n      \"atributoId\": 3,\n      \"valor\": (if (.marca) .marca else \"TOYOTA\")\n    },\n    {\n      \"atributoId\": 4,\n      \"valor\": (if (.modelo) .modelo else \"PRIUS\")\n    },\n    {\n      \"atributoId\": 5,\n      \"valor\": (if (.anio) number(.anio) else 2026)\n    }\n  ]\n}"
  },
  "retryPolicy": {
    "maxAttempts": 3,
    "backoffMs": 1000
  },
  "rateLimitPolicy": {
    "requestsPerSecond": 20
  }
}
```

---

### 4.2. Ejemplo INBOUND: Polling de Clientes desde API REST Externa

Este perfil consulta periódicamente una API REST externa para extraer nuevos clientes:

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
  "businessDomain": "customers",
  "externalSource": "crm-external-api",
  "syncDirection": "INBOUND",
  "sourceOfTruth": "SOURCE",
  "protocol": "REST",
  "connector": "crm-api-connector",
  "adapter": "generic-http-adapter",
  "endpoint": "https://api.crm-partner.com",
  "credentialRef": "secret/crm/api-credentials",
  "extractionConfig": {
    "method": "GET",
    "path": "/api/v2/customers",
    "queryParams": {
      "updatedSince": ":lastSyncWithBuffer",
      "limit": "100"
    },
    "watermarkParam": "updatedSince",
    "watermarkFormat": "ISO_8601",
    "responseJsonPath": "$.items[*]",
    "keyProperty": "customerId"
  },
  "transformation": {
    "engine": "JSLT",
    "script": "{\n  \"customerId\": .id,\n  \"name\": .full_name,\n  \"email\": .contact_email,\n  \"tier\": lookup(\"CUSTOMER_TIERS\", .loyalty_level, \"STANDARD\")\n}"
  },
  "syncPolicy": {
    "cronExpression": "0 0 * * * *"
  },
  "retryPolicy": {
    "maxAttempts": 3,
    "backoffMs": 1500
  }
}
```

---

## 5. Visualización en Modo DEBUG y Enmascaramiento de Datos Sensibles

Al habilitar `logging.level.com.cl2.integration.adapter.out.http.HttpOutboundClient=DEBUG`, el despachador registra en consola y logs el detalle completo de la petición:

```text
DEBUG HttpOutboundClient : HTTP Outbound Request -> POST https://api.qa.comsatel.com.pe/unidad/api/v1/unidad
Headers: {Content-Type=application/json, Accept=application/json, X-Distribuidor-Id=1, x-audit=424234234, Authorization=Bearer eyJ[REDACTED]}
Payload: {
  "tipoUnidadId" : 2,
  "externoId" : "123456777896544",
  "alias" : "C2Q145",
  "propietarioId" : 45,
  "creadorUsuarioId" : "45E[REDACTED]",
  "fuenteId" : 1,
  "atributosUnidad" : [ ... ]
}
```

- **Regla de Enmascaramiento**: Mantiene los primeros **3 caracteres visibles** de las credenciales y sustituye el resto por `"[REDACTED]"`.
- **Protección Automática**: Aplica tanto en cabeceras HTTP de autorización (`Bearer`, `Basic`, `X-API-Key`) como en campos sensibles del payload JSON (`password`, `client_secret`, `token`, `apiKey`).
