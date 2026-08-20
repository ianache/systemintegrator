# Guía de Configuración y Pruebas de Integración Outbound (Despacho HTTP)

Esta guía describe cómo configurar y probar el despacho de eventos desde el bus Apache Kafka (`integration.<domain>.events`) hacia APIs REST / Webhooks externos o microservicios Core de CL2 asegurados con Keycloak mediante perfiles de integración `OUTBOUND` o `BIDIRECTIONAL`.

---

## 1. Arquitectura de Despacho Outbound

```mermaid
flowchart LR
    A[Kafka Topics: integration.<domain>.events\n(brands, models, vehicles, etc.)] --> B[KafkaInboxListener (Regex: integration.*.events)]
    B --> C[InboxProcessor (Idempotencia en integration_inbox)]
    C --> D[OutboundEventDispatcher]
    D -->|1. Filtra Perfiles OUTBOUND y Anti-Loop| E[(MySQL: integration_profile)]
    D -->|2. Resuelve Secreto OAuth2 / Basic| F[(HashiCorp Vault)]
    D -->|3. Transforma Payload| G[TransformationService]
    D -->|4. Obtiene / Cachea Bearer JWT| H[OAuth2TokenCacheManager / Keycloak]
    D -->|5. Envío HTTP con Resiliencia| I[API REST / CL2 Core Microservices]
    I -->|2xx OK| J[integration_inbox: PROCESSED]
    I -->|Fallo Agotado| K[integration.events.dlq + DEAD_LETTER]
```

---

## 2. Configuración del Perfil de Integración

Para habilitar el despacho, se requiere un perfil con `protocol: "REST"` y dirección `OUTBOUND` (o `BIDIRECTIONAL`).

### Opción A: Crear un Nuevo Perfil Outbound (Ejemplo: Dominio Vehicles)

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
    "connector": "cl2-vehicles-api",
    "adapter": "cl2-vehicles-adapter",
    "endpoint": "https://api.cl2.com/api/v1/vehicles",
    "credentialRef": "secret/cl2/keycloak-credentials",
    "mapping": {
      "vin": "numero_motor",
      "brandCode": "marca",
      "modelCode": "modelo",
      "modelYear": "anio"
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

### Opción B: Perfiles de Catálogo Maestro (`brands` y `models`)

#### Perfil Brands Outbound
```json
{
  "businessDomain": "brands",
  "externalSource": "cl2-core",
  "syncDirection": "OUTBOUND",
  "sourceOfTruth": "PLATFORM",
  "protocol": "REST",
  "connector": "cl2-brands-api",
  "adapter": "cl2-brands-adapter",
  "endpoint": "https://api.cl2.com/api/v1/brands",
  "credentialRef": "secret/cl2/keycloak-credentials",
  "mapping": {
    "code": "codigo_marca",
    "name": "nombre_marca",
    "active": "activo"
  }
}
```

#### Perfil Models Outbound
```json
{
  "businessDomain": "models",
  "externalSource": "cl2-core",
  "syncDirection": "OUTBOUND",
  "sourceOfTruth": "PLATFORM",
  "protocol": "REST",
  "connector": "cl2-models-api",
  "adapter": "cl2-models-adapter",
  "endpoint": "https://api.cl2.com/api/v1/models",
  "credentialRef": "secret/cl2/keycloak-credentials",
  "mapping": {
    "code": "codigo_modelo",
    "name": "nombre_modelo",
    "brandCode": "codigo_marca",
    "active": "activo"
  }
}
```

---

## 3. Configuración de Credenciales en Vault (`credentialRef`)

El `credentialRef` indica la ruta del secreto en HashiCorp Vault. El despachador inyecta las cabeceras HTTP automáticamente según el tipo de autenticación:

### 1. OAuth2 Client Credentials / Keycloak (JWT Dinámico con Cache)
* **Ruta en Vault**: `secret/data/cl2/keycloak-credentials`
* **JSON del secreto**:
  ```json
  {
    "tokenUrl": "https://oauth2.qa.comsatel.com.pe/realms/microservicios/protocol/openid-connect/token",
    "clientId": "cl2integration",
    "clientSecret": "TU_CLIENT_SECRET_AQUI",
    "scope": "openid"
  }
  ```
* *Comportamiento*: `OAuth2TokenCacheManager` invoca el token endpoint con `grant_type=client_credentials`, cachea el token en memoria por tenant/clientId y añade la cabecera `Authorization: Bearer <JWT>`.

### 2. Bearer Token Estático
* **JSON del secreto**:
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
  ```
* *Cabecera inyectada*: `Authorization: Bearer <token>`

### 3. Basic Auth (Usuario y Contraseña)
* **JSON del secreto**:
  ```json
  {
    "username": "api_user",
    "password": "api_password_123"
  }
  ```
* *Cabecera inyectada*: `Authorization: Basic <base64(username:password)>`

### 4. API Key
* **JSON del secreto**:
  ```json
  {
    "apiKey": "mi-api-key-secreta"
  }
  ```
* *Cabecera inyectada*: `X-API-Key: mi-api-key-secreta`

---

## 4. Flujo de Ejecución, Idempotencia y Manejo de Errores

1. **Ingesta de Eventos**: El mensaje llega al tópico `integration.<domain>.events` con headers de procedencia: `X-Tenant-ID`, `X-Event-Type`, `X-Aggregate-ID`, `X-Business-Domain` y `X-External-Source`.
2. **Idempotencia**: `InboxProcessor` valida contra la tabla `integration_inbox`. Si el `eventId` ya fue procesado (`PROCESSED`), se descarta para evitar duplicados.
3. **Filtro Anti-Loop**: Se descartan perfiles cuyo `externalSource` coincida con `X-External-Source`.
4. **Despacho Resiliente**: `OutboundEventDispatcher` envía la petición con circuit breaker (`Resilience4j`) y rate limiting (`Redis Token Bucket`).
5. **Manejo de Respuestas**:
   - **Éxito (2xx)**: El registro en `integration_inbox` se marca como `PROCESSED`.
   - **Fallo Definitivo (5xx / Timeout agotado)**: El estado cambia a `DEAD_LETTER` y el evento se redirige al tópico Kafka `integration.events.dlq` con las cabeceras de error correspondientes.
