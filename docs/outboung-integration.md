# Guía de Configuración y Pruebas de Integración Outbound (Despacho HTTP)

Esta guía describe cómo configurar y probar el despacho de eventos desde el bus Apache Kafka (`integration.events`) hacia APIs REST / Webhooks externos mediante perfiles de integración `OUTBOUND` o `BIDIRECTIONAL`.

---

## 1. Arquitectura de Despacho Outbound

```mermaid
flowchart LR
    A[Kafka Topic: integration.events] --> B[KafkaInboxListener]
    B --> C[InboxProcessor (Idempotencia en integration_inbox)]
    C --> D[OutboundEventDispatcher]
    D -->|1. Busca Perfil Activo OUTBOUND| E[(MySQL: integration_profile)]
    D -->|2. Resuelve Credenciales| F[(HashiCorp Vault)]
    D -->|3. Transforma Payload| G[TransformationService]
    D -->|4. Envío HTTP con Resiliencia| H[API REST / Webhook Destino]
    H -->|2xx OK| I[integration_inbox: PROCESSED]
    H -->|Fallo Agotado| J[integration.events.dlq + DEAD_LETTER]
```

---

## 2. Configuración del Perfil de Integración

Para habilitar el despacho, se requiere un perfil con `protocol: "REST"` y dirección `OUTBOUND` (o `BIDIRECTIONAL`).

### Opción A: Crear un Nuevo Perfil Outbound

* **Método**: `POST`
* **URL**: `http://localhost:8081/api/v1/integration-profiles` (o `http://localhost:8080/api/v1/integration-profiles`)
* **Headers**:
  ```http
  Authorization: Bearer <TU_TOKEN_JWT>
  X-Tenant-ID: 11111111-1111-1111-1111-111111111111
  Content-Type: application/json
  ```
* **Payload JSON**:
  ```json
  {
    "businessDomain": "vehicles",
    "externalSource": "target-api",
    "syncDirection": "OUTBOUND",
    "sourceOfTruth": "PLATFORM",
    "protocol": "REST",
    "connector": "generic-http",
    "adapter": "generic-http-adapter",
    "endpoint": "https://api.miempresa.com/v1/vehiculos",
    "credentialRef": "secret/target/api-credentials",
    "mapping": {
      "plateNumber": "placa",
      "engineNumber": "motor",
      "manufacturingYear": "anio",
      "vehicleColor": "color"
    },
    "retryPolicy": {
      "maxAttempts": 3,
      "backoffMs": 1000
    },
    "rateLimitPolicy": {
      "requestsPerSecond": 50
    }
  }
  ```

---

### Opción B: Actualizar un Perfil Existente a `BIDIRECTIONAL`

* **Método**: `PUT`
* **URL**: `http://localhost:8081/api/v1/integration-profiles/{profileId}`
* **Headers**:
  ```http
  Authorization: Bearer <TU_TOKEN_JWT>
  X-Tenant-ID: 11111111-1111-1111-1111-111111111111
  Content-Type: application/json
  ```
* **Payload JSON**:
  ```json
  {
    "businessDomain": "vehicles",
    "externalSource": "sigo",
    "syncDirection": "BIDIRECTIONAL",
    "sourceOfTruth": "EXTERNAL",
    "expectedVersion": 0,
    "protocol": "REST",
    "connector": "generic-http",
    "adapter": "generic-http-adapter",
    "endpoint": "https://api.miempresa.com/v1/vehiculos",
    "credentialRef": "secret/target/api-credentials",
    "mapping": {
      "plate": "placa",
      "engine": "motor"
    },
    "syncPolicy": {
      "cronExpression": "0 */10 * * * *"
    }
  }
  ```

---

## 3. Configuración de Credenciales en Vault (`credentialRef`)

El `credentialRef` indica la ruta del secreto en HashiCorp Vault. El despachador inyecta las cabeceras HTTP automáticamente según las claves del secreto:

### 1. Bearer Token (JWT / OAuth2 / Token estático)
* **Ruta en Vault**: `secret/data/target/api-credentials`
* **JSON del secreto**:
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
  ```
* *Cabecera inyectada*: `Authorization: Bearer <token>`

### 2. Basic Auth (Usuario y Contraseña)
* **JSON del secreto**:
  ```json
  {
    "username": "api_user",
    "password": "api_password_123"
  }
  ```
* *Cabecera inyectada*: `Authorization: Basic <base64(username:password)>`

### 3. API Key
* **JSON del secreto**:
  ```json
  {
    "apiKey": "mi-api-key-secreta"
  }
  ```
* *Cabecera inyectada*: `X-API-Key: mi-api-key-secreta`

### 4. Cabeceras Personalizadas (Custom Headers)
* **JSON del secreto**:
  ```json
  {
    "headers": {
      "X-Custom-Auth": "Token-12345",
      "X-Partner-Id": "partner-99"
    }
  }
  ```

---

## 4. Flujo de Ejecución y Manejo de Errores

1. **Ingesta de Eventos**: Un mensaje llega al tópico `integration.events` con headers `X-Tenant-ID` y `X-Event-Type` (ej. `vehicle.upserted` o `customer.upserted`).
2. **Idempotencia**: `InboxProcessor` valida contra la tabla `integration_inbox`. Si el `eventId` ya fue procesado con éxito (`PROCESSED`), se descarta de forma segura para evitar llamadas duplicadas.
3. **Despacho Resiliente**: `OutboundEventDispatcher` envía la petición con circuit breaker y políticas de retry (`retryPolicy`).
4. **Manejo de Respuestas**:
   - **Éxito (2xx)**: El registro en `integration_inbox` se marca como `PROCESSED`.
   - **Fallo Definitivo (5xx / Timeout)**: Tras agotar reintentos, el estado en `integration_inbox` cambia a `DEAD_LETTER` y el evento se redirige al tópico Kafka `integration.events.dlq` con la causa del error en las cabeceras.
