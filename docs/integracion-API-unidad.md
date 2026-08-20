# Guía de Integración Outbound: Registro de Unidad en API Comsatel

Este documento detalla la configuración integral (perfil de integración, secreto en Vault y transformación JSLT) para despachar eventos del dominio de unidades/vehículos hacia el API REST de registro de unidades de Comsatel QA asegurada con Keycloak.

---

## 1. Petición HTTP Objetivo (`curl`)

El objetivo es que el despachador Outbound replique de forma autónoma, resiliente y autenticada la siguiente petición HTTP:

```bash
curl -X 'POST' \
  'https://api.qa.comsatel.com.pe/unidad/api/v1/unidad' \
  -H 'accept: application/json' \
  -H 'x-audit: 424234234' \
  -H 'X-Distribuidor-Id: 1' \
  -H 'Authorization: Bearer <TOKEN_JWT_KEYCLOAK>' \
  -H 'Content-Type: application/json' \
  -d '{
  "tipoUnidadId": 2,
  "externoId": "123456777896544",
  "alias": "C2Q145",
  "propietarioId": 45,
  "creadorUsuarioId": "45ERT34F453534F",
  "fuenteId": 1,
  "atributosUnidad": [
    {
      "atributoId": 1,
      "valor": "C2Q145"
    },
    {
      "atributoId": 2,
      "valor": "938373564D1D423"
    },
    {
      "atributoId": 3,
      "valor": "TOYOTA"
    },
    {
      "atributoId": 4,
      "valor": "PRIUS"
    },
    {
      "atributoId": 5,
      "valor": 2026
    }
  ]
}'
```

---

## 2. Configuración de Credenciales y Cabeceras en Vault (`credentialRef`)

El perfil de integración referencia un secreto en HashiCorp Vault. Para este caso, se configuran las credenciales OAuth2 de Keycloak junto con las cabeceras personalizadas fijas (`x-audit` y `X-Distribuidor-Id`).

* **Ruta en Vault**: `secret/data/cl2/comsatel-unidad-credentials`
* **JSON del Secreto**:
```json
{
  "tokenUrl": "https://oauth2.qa.comsatel.com.pe/realms/microservicios/protocol/openid-connect/token",
  "clientId": "cl2integration",
  "clientSecret": "TU_CLIENT_SECRET_KEYCLOAK",
  "scope": "openid",
  "headers": {
    "x-audit": "424234234",
    "X-Distribuidor-Id": "1"
  }
}
```

> **Comportamiento**: `HttpOutboundClient` y `OAuth2TokenCacheManager` obtienen o reutilizan el Bearer JWT desde Keycloak e inyectan automáticamente todas las cabeceras definidas en `headers`.

---

## 3. Creación del Integration Profile (`OUTBOUND`)

Se registra un perfil de integración de tipo `OUTBOUND` para el dominio `unidades` (o `vehicles`).

### Petición al API de Perfiles:
* **Método**: `POST`
* **URL**: `http://localhost:8080/api/v1/integration-profiles`
* **Headers**:
  ```http
  Authorization: Bearer <TOKEN_JWT_ADMIN>
  X-Tenant-ID: 11111111-1111-1111-1111-111111111113
  Content-Type: application/json
  ```

### Payload JSON de Creación:
```json
{
  "businessDomain": "unidades",
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

## 4. Detalle del Script de Transformación Declarativo (JSLT)

El motor JSLT convierte la estructura plana del evento Inbound en la estructura jerárquica con arreglos de atributos:

```javascript
{
  "tipoUnidadId": 2,
  "externoId": if (.externo_id) .externo_id else if (.numero_motor) .numero_motor else "0",
  "alias": .numero_placa,
  "propietarioId": if (.propietario_id) .propietario_id else 45,
  "creadorUsuarioId": if (.usuario_id) .usuario_id else "45ERT34F453534F",
  "fuenteId": 1,
  "atributosUnidad": [
    {
      "atributoId": 1,
      "valor": .numero_placa
    },
    {
      "atributoId": 2,
      "valor": .numero_motor
    },
    {
      "atributoId": 3,
      "valor": if (.marca) .marca else "TOYOTA"
    },
    {
      "atributoId": 4,
      "valor": if (.modelo) .modelo else "PRIUS"
    },
    {
      "atributoId": 5,
      "valor": if (.anio) number(.anio) else 2026
    }
  ]
}
```

---

## 5. Flujo de Ejecución en Tiempo de Ejecución

```
1. Extracción Inbound (SIGO/JDBC)
   │
   ▼
2. Publicación Kafka: integration.unidades.events
   │
   ▼
3. KafkaInboxListener (Consumer deduplicado)
   │
   ▼
4. OutboundEventDispatcher
   ├── Ejecuta transformación JSLT
   ├── Resuelve secret/cl2/comsatel-unidad-credentials en Vault
   ├── OAuth2TokenCacheManager obtiene/reutiliza Bearer JWT de Keycloak
   └── ResilienceExecutor + HttpOutboundClient envían POST HTTP
   │
   ▼
5. Endpoint https://api.qa.comsatel.com.pe/unidad/api/v1/unidad recibe HTTP 2xx
   │
   ▼
6. Estado en integration_inbox actualizado a PROCESSED
```
