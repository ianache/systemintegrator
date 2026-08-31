# Plan de Pruebas y Casos de Prueba: Motor de Transformación de Payloads (Transformation Engine)

## 1. Información General

| Campo | Valor |
|---|---|
| Módulo | Payload Transformation Engine (Slice 3) |
| Paquete Base | `com.cl2.integration.integration.transformation` |
| Motores Soportados | `PASSTHROUGH`, `FIELD_MAPPING`, `JSLT` |
| Entorno de ejecución | Gateway con Keycloak QA (`http://127.0.0.1:8081`) o llamada directa (`http://localhost:8080`) |
| Base de Datos | MySQL 8.4 (`integration_profile`) |
| Compatibilidad | Campo `configuration.transformation` con fallback a `configuration.mapping` |

---

## 2. Estrategias del Motor de Transformación

```
                          ┌──────────────────────────┐
                          │    Source JSON Payload   │
                          └─────────────┬────────────┘
                                        │
                                        ▼
                          ┌──────────────────────────┐
                          │  TransformationService   │
                          └─────────────┬────────────┘
                                        │ (Detects engine)
          ┌─────────────────────────────┼────────────────────────────┐
          ▼                             ▼                            ▼
┌───────────────────┐         ┌───────────────────┐        ┌───────────────────┐
│    PASSTHROUGH    │         │   FIELD_MAPPING   │        │       JSLT        │
│   (Default / NoOp)│         │ (JSONPath + SpEL) │        │ (Declarative JSON)│
└───────────────────┘         └───────────────────┘        └───────────────────┘
```

1. **`PASSTHROUGH`**: Devuelve el payload original sin modificaciones cuando no hay configuración o el motor es passthrough.
2. **`FIELD_MAPPING`**: Mapeo campo por campo utilizando expresiones JSONPath para extracción, casting opcional de tipos (`STRING`, `INTEGER`, `LONG`, `DOUBLE`, `BOOLEAN`, `JSON`), expresiones Spring SpEL para transformaciones complejas y validación de campos obligatorios (`required: true`).
3. **`JSLT`**: Transformaciones declarativas JSON a JSON de alto rendimiento utilizando scripts JSLT con soporte para filtros, mapeos de arrays y funciones personalizadas.

---

## 3. Matriz de Pruebas Automatizadas (Unit & Integration)

| Clase de Prueba | Tipo | Casos Cubiertos | Resultado |
|---|---|---|---|
| `PassthroughPayloadTransformerTest` | Unit | Passthrough de JSON válido, JSON malformado, payload vacío/null, validación de configuración. | PASSED |
| `FieldMappingPayloadTransformerTest` | Unit | Extracción JSONPath simple y anidada, transformaciones SpEL (string, matemáticas, booleanas), conversión de tipos destino, validación de `required` (lanza `MissingRequiredFieldException`), validación de configuración inválida/malformada. | PASSED |
| `JsltPayloadTransformerTest` | Unit | Transformación JSLT declarativa, funciones de transformación nativas, objetos anidados, sintaxis de script inválida (lanza `TransformationException`), validación de configuración. | PASSED |
| `TransformationServiceIntegrationTest` | Integration | Orquestación mediante `IntegrationProfile` con `FIELD_MAPPING`, orquestación con `JSLT`, fallback a `PASSTHROUGH` cuando no hay configuración o perfil es nulo, validación de configuraciones con `@SpringBootTest`. | PASSED |

---

## 4. Matriz de Casos de Prueba Manuales

| ID | Motor / Funcionalidad | Objetivo | Configuración de Transformación | Payload Origen | Resultado Esperado |
|---|---|---|---|---|---|
| **PTE-01** | `FIELD_MAPPING` | Mapeo directo y transformación SpEL | `{"engine":"FIELD_MAPPING","fields":[{"target":"vin","sourcePath":"$.NumeroChasis","required":true},{"target":"brand","sourcePath":"$.Marca","transform":"#val.toUpperCase()"}]}` | `{"NumeroChasis":"VIN123","Marca":"nissan"}` | `{"vin":"VIN123","brand":"NISSAN"}` |
| **PTE-02** | `FIELD_MAPPING` | Conversión de tipo (Casting) | `{"engine":"FIELD_MAPPING","fields":[{"target":"active","sourcePath":"$.estado","targetType":"BOOLEAN"},{"target":"count","sourcePath":"$.cantidad","targetType":"INTEGER"}]}` | `{"estado":"true","cantidad":"42"}` | `{"active":true,"count":42}` |
| **PTE-03** | `FIELD_MAPPING` | Validación de campo requerido ausente | `{"engine":"FIELD_MAPPING","fields":[{"target":"id","sourcePath":"$.id","required":true}]}` | `{"name":"test"}` | Error `400 / 422` `MissingRequiredFieldException: Required field 'id' is missing` |
| **PTE-04** | `FIELD_MAPPING` | Fallback / Valor por defecto | `{"engine":"FIELD_MAPPING","fields":[{"target":"tier","sourcePath":"$.tipo","defaultValue":"STANDARD"}]}` | `{"name":"Juan"}` | `{"tier":"STANDARD"}` |
| **PTE-05** | `JSLT` | Transformación declarativa de atributos | `{"engine":"JSLT","script":"{ \"customerCode\": .KUNNR, \"name\": .NAME1 }"}` | `{"KUNNR":"00100","NAME1":"DISTRIBUIDORA"}` | `{"customerCode":"00100","name":"DISTRIBUIDORA"}` |
| **PTE-06** | `JSLT` | Transformación con array y filtro | `{"engine":"JSLT","script":"{ \"activeItems\": [for (.items) . if (.active == true)] }"}` | `{"items":[{"id":1,"active":true},{"id":2,"active":false}]}` | `{"activeItems":[{"id":1,"active":true}]}` |
| **PTE-07** | `JSLT` | Script JSLT con sintaxis inválida | `{"engine":"JSLT","script":"{ invalid json/jslt syntax "}` | `{"id":1}` | Error `400 / 422` `TransformationException: Invalid JSLT syntax` |
| **PTE-08** | `PASSTHROUGH` | Perfil sin configuración de transformación | `null` o `{}` | `{"raw":"data"}` | `{"raw":"data"}` intacto |
| **PTE-09** | `PASSTHROUGH` | Configuración explícita PASSTHROUGH | `{"engine":"PASSTHROUGH"}` | `{"message":"hello"}` | `{"message":"hello"}` intacto |
| **PTE-10** | `ORCHESTRATOR` | Detección automática por presencia de `fields` | `{"fields":[{"target":"code","sourcePath":"$.codigo"}]}` | `{"codigo":"ABC"}` | Motor `FIELD_MAPPING` detectado, resultado `{"code":"ABC"}` |
| **PTE-11** | `ORCHESTRATOR` | Detección automática por presencia de `script` | `{"script":"{ \"id\": .id }"}` | `{"id":"XYZ"}` | Motor `JSLT` detectado, resultado `{"id":"XYZ"}` |
| **PTE-12** | `ORCHESTRATOR` | Retrocompatibilidad campo `mapping` | `mapping: "{\"engine\":\"FIELD_MAPPING\",\"fields\":[{\"target\":\"id\",\"sourcePath\":\"$.id\"}]}"`, `transformation: null` | `{"id":"99"}` | Utiliza `mapping` si `transformation` no está definido |

---

## 5. Guía de Ejecución en PowerShell

### 5.1 Obtener Token Keycloak

```powershell
$env:KEYCLOAK_ISSUER_URI = 'https://oauth2.qa.comsatel.com.pe/realms/microservicios'
$env:KEYCLOAK_CLIENT_ID = 'cl2integration'
$env:KEYCLOAK_CLIENT_SECRET = 'lMFdDxHeSb4BwQIVJXtAK21ujlTp6yTS'
$env:KEYCLOAK_USERNAME = 'integracion'
$securePassword = Read-Host 'Keycloak password' -AsSecureString
$env:KEYCLOAK_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password

$tokenResponse = Invoke-RestMethod -Method Post `
  -Uri "$env:KEYCLOAK_ISSUER_URI/protocol/openid-connect/token" `
  -ContentType 'application/x-www-form-urlencoded' `
  -Body @{ 
    grant_type    = 'password'
    client_id     = $env:KEYCLOAK_CLIENT_ID
    client_secret = $env:KEYCLOAK_CLIENT_SECRET
    username      = $env:KEYCLOAK_USERNAME
    password      = $env:KEYCLOAK_PASSWORD 
  }

$env:ACCESS_TOKEN = $tokenResponse.access_token
$GATEWAY_URL = "http://127.0.0.1:8081"
$headers = @{
    "Authorization" = "Bearer $env:ACCESS_TOKEN"
    "Content-Type"  = "application/json"
}
```

---

### 5.2 Caso PTE-01: Crear Perfil con `FIELD_MAPPING` y Transformación SpEL

```powershell
$body = @{
    businessDomain   = "vehicles-sigo-transform"
    externalSource   = "sigo-adapter"
    syncDirection    = "INBOUND"
    sourceOfTruth    = "EXTERNAL"
    protocol         = "REST"
    connector        = "sigo"
    adapter          = "sigo-http"
    endpoint         = "https://sigo.qa.internal/api"
    transformation   = @{
        engine = "FIELD_MAPPING"
        fields = @(
            @{ target = "vin"; sourcePath = "$.NumeroChasis"; required = $true },
            @{ target = "brand"; sourcePath = "$.Marca"; transform = "#val.toUpperCase()" },
            @{ target = "priceWithTax"; sourcePath = "$.Precio"; transform = "#val * 1.18"; targetType = "DOUBLE" }
        )
    } | ConvertTo-Json -Depth 5
}

$profile = Invoke-RestMethod -Method Post `
  -Uri "$GATEWAY_URL/api/v1/integration-profiles" `
  -Headers $headers `
  -Body ($body | ConvertTo-Json -Depth 5)

Write-Host "Perfil Creado:" $profile.id
```

---

### 5.3 Caso PTE-05: Crear Perfil con Motor `JSLT`

```powershell
$body = @{
    businessDomain   = "customers-sap-jslt"
    externalSource   = "sap-erp"
    syncDirection    = "INBOUND"
    sourceOfTruth    = "EXTERNAL"
    protocol         = "REST"
    connector        = "sap"
    adapter          = "sap-rfc"
    endpoint         = "https://sap.qa.internal/rfc"
    transformation   = @{
        engine = "JSLT"
        script = '{ "customerCode": .KUNNR, "legalName": .NAME1, "city": .ORT01 }'
    } | ConvertTo-Json -Depth 5
}

$profile = Invoke-RestMethod -Method Post `
  -Uri "$GATEWAY_URL/api/v1/integration-profiles" `
  -Headers $headers `
  -Body ($body | ConvertTo-Json -Depth 5)

Write-Host "Perfil JSLT Creado:" $profile.id
```

---

### 5.4 Caso PTE-03: Validación de Configuración Rechazada (Error de Sintaxis)

```powershell
$badBody = @{
    businessDomain   = "invalid-jslt-profile"
    externalSource   = "test-source"
    syncDirection    = "INBOUND"
    sourceOfTruth    = "EXTERNAL"
    protocol         = "REST"
    connector        = "test"
    adapter          = "test-adapter"
    transformation   = @{
        engine = "JSLT"
        script = '{ syntax error missing colon }'
    } | ConvertTo-Json -Depth 5
}

try {
    Invoke-RestMethod -Method Post `
      -Uri "$GATEWAY_URL/api/v1/integration-profiles" `
      -Headers $headers `
      -Body ($badBody | ConvertTo-Json -Depth 5)
} catch {
    Write-Host "Error HTTP Esperado:" $_.Exception.Response.StatusCode
}
```
