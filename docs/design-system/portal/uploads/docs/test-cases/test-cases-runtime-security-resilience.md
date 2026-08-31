# Plan de Pruebas y Casos de Prueba: Runtime Security & Resilience (Secret Resolution, Rate Limiting & Circuit Breaking)

## 1. Información General

| Campo | Valor |
|---|---|
| Módulo | Runtime Security & Resilience (Slice 4) |
| Paquetes Base | `com.cl2.integration.integration.security`, `com.cl2.integration.integration.resilience` |
| Componentes Principales | `VaultSecretResolver`, `InMemorySecretResolver`, `RedisDistributedRateLimiter`, `ResilienceExecutor` |
| Tecnologías / Librerías | HashiCorp Vault, Redis 7 (Token Bucket / Sliding Window), Resilience4j 2.2, Spring Boot 3.4 |
| Entorno de ejecución | Docker Compose (`vault:8200`, `redis:6379`, `mysql:8.4`, `app:8080`, `middleware:8081`) |
| Compatibilidad Multi-Tenant | Aislamiento por `tenantId` en almacenamiento de secretos, cuotas de rate limiting y estados de circuit breaker |

---

## 2. Arquitectura de Seguridad y Resiliencia en Runtime

```
                                  ┌────────────────────────┐
                                  │   Integration Engine   │
                                  │   (Outbound Pipeline)  │
                                  └───────────┬────────────┘
                                              │
                       1. Resolve Credential  │ (credentialRef + tenantId)
                                              ▼
                                 ┌────────────────────────┐
                                 │     SecretResolver     │──(Cache TTL)──► [ InMemoryCache ]
                                 │ (Vault / In-Memory)    │
                                 └────────────┬───────────┘
                                              │ 2. Check Quota
                                              ▼
                                 ┌────────────────────────┐
                                 │ DistributedRateLimiter │──(Token Bucket)──► [ Redis 7 ]
                                 │ (Redis / Distributed)  │
                                 └────────────┬───────────┘
                                              │ 3. Fault-Tolerant Call
                                              ▼
                                 ┌────────────────────────┐
                                 │   ResilienceExecutor   │──(State Tracking)──► [ Resilience4j ]
                                 │    (Circuit Breaker)   │
                                 └────────────┬───────────┘
                                              │
                                              ▼ 4. HTTP / REST Call
                                 ┌────────────────────────┐
                                 │    External System     │
                                 │   (SAP / CRM / ERP)    │
                                 └────────────────────────┘
```

1. **`SecretResolver`**: Resuelve referencias opacas (`credentialRef`, ej. `vault:secret/data/tenants/{tenantId}/sap`) a credenciales vivas (`ResolvedSecret` en variantes Bearer Token, Basic Auth, API Key), asegurando que ninguna contraseña o token plano resida en la base de datos de perfiles y optimizando mediante caché TTL local.
2. **`DistributedRateLimiter`**: Controla el consumo y velocidad de peticiones salientes por conector y por tenant utilizando algoritmos distribuidos sobre Redis (`tryAcquire` / `checkPermission`) con soporte para ventanas de `SECOND`, `MINUTE`, `HOUR` y `DAY`.
3. **`ResilienceExecutor`**: Protege la infraestructura contra fallos en cascada aislando los adaptadores de integración mediante Circuit Breakers con Resilience4j configurados con umbrales de fallo, ventana deslizante y estados `CLOSED`, `OPEN` y `HALF_OPEN`.

---

## 3. Matriz de Pruebas Automatizadas (Unit & Integration)

| Clase de Prueba | Tipo | Casos Cubiertos | Resultado |
|---|---|---|---|
| `SecretResolverTest` | Unit | Resolución de Bearer Token, Basic Auth y API Key; validación de aislamiento multi-tenant; caché en memoria y expiración por TTL; lanzamiento de `SecretNotFoundException` ante credenciales nulas o inexistentes. | PASSED |
| `DistributedRateLimiterTest` | Unit | Adquisición exitosa de cuota (`tryAcquire`); bloqueo con `allowed = false` y `retryAfterSeconds` al exceder la cuota; lanzamiento de `RateLimitExceededException` en `checkPermission`; aislamiento de cuotas independientes entre diferentes tenants. | PASSED |
| `ResilienceExecutorTest` | Unit | Ejecución exitosa en estado `CLOSED`; transición a `OPEN` tras superar umbral de errores; bloqueo de llamadas subsiguientes con `CircuitBreakerOpenException`; aislamiento de circuit breakers por conector y tenant. | PASSED |
| `RuntimeSecurityResilienceIntegrationTest` | Integration | Orquestación end-to-end con `@SpringBootTest`: Resolución de credencial desde `IntegrationProfileConfiguration`, verificación de cuota en `DistributedRateLimiter` y ejecución protegida a través de `ResilienceExecutor`. | PASSED |

---

## 4. Matriz de Casos de Prueba Manuales (RSR-01 a RSR-10)

| ID | Componente | Objetivo | Precondición / Entrada | Acción / Operación | Resultado Esperado |
|---|---|---|---|---|---|
| **RSR-01** | `SecretResolver` | Resolución exitosa de credencial Bearer Token | Secret presente en Vault o Resolver para `tenantId` y `credentialRef = "vault:secret/data/tenants/{id}/sap"` | `secretResolver.resolve(credentialRef, tenantId)` | Retorna `ResolvedSecret` con `type = BEARER` y token válido sin exponer contraseñas en logs. |
| **RSR-02** | `SecretResolver` | Resolución exitosa de credenciales Basic Auth y API Key | Secrets registrados para autenticación por usuario/contraseña y por header de API Key | `secretResolver.resolve(basicRef, tenantId)` y `secretResolver.resolve(apiKeyRef, tenantId)` | Retorna `ResolvedSecret` con tipo `BASIC_AUTH` (`username`, `password`) y `API_KEY` (`headerName`, `apiKey`). |
| **RSR-03** | `SecretResolver` | Caché en memoria de credenciales con TTL | Secret cargado previamente con TTL de 5 minutos | Múltiples resoluciones consecutivas de la misma credencial | Las llamadas subsecuentes se atienden desde caché local sin invocar al backend de Vault. |
| **RSR-04** | `SecretResolver` | Manejo de credencial inexistente | Referencia inexistente `vault:secret/data/invalid/path` o `null` | `secretResolver.resolve(invalidRef, tenantId)` | Lanza `SecretNotFoundException` con código `404 / 422` y mensaje descriptivo. |
| **RSR-05** | `DistributedRateLimiter` | Adquisición de permisos dentro de cuota permitida | Conector configurado con límite de 100 req/min para `tenantA` | `rateLimiter.tryAcquire(tenantA, "sap", 100, "MINUTE")` | Retorna `RateLimitResult[allowed=true, remaining=99, retryAfterSeconds=0]`. |
| **RSR-06** | `DistributedRateLimiter` | Bloqueo por exceso de tasa y cálculo de backoff | Conector con cuota agotada (0 tokens disponibles en la ventana actual) | `rateLimiter.tryAcquire(tenantA, "sap", 5, "SECOND")` tras 5 llamadas inmediatas | Retorna `allowed=false` con `retryAfterSeconds > 0`. En invocación `checkPermission()` lanza `RateLimitExceededException`. |
| **RSR-07** | `DistributedRateLimiter` | Aislamiento multi-tenant en Rate Limiting | `tenantA` ha consumido el 100% de su cuota para el conector `"salesforce"` | `rateLimiter.tryAcquire(tenantB, "salesforce", 50, "MINUTE")` | `tenantB` obtiene `allowed=true` sin verse afectado por el consumo de `tenantA`. |
| **RSR-08** | `ResilienceExecutor` | Ejecución protegida exitosa en estado `CLOSED` | Circuit Breaker en estado inicial `CLOSED` | `resilienceExecutor.execute(tenantA, "sap", () -> callRemoteService())` | La llamada remota se ejecuta normalmente y retorna el resultado exitoso (ej. `HTTP 200`). |
| **RSR-09** | `ResilienceExecutor` | Apertura de Circuit Breaker (`OPEN`) ante ráfaga de errores | Fallos consecutivos en servicio remoto superando el umbral de tasa de fallos (50%) | Ejecución de operaciones fallidas continuas | El Circuit Breaker pasa a estado `OPEN`. Llamadas siguientes se bloquean inmediatamente lanzando `CircuitBreakerOpenException` sin sobrecargar el servicio remoto. |
| **RSR-10** | `ResilienceExecutor` | Recuperación a `HALF_OPEN` y restauración a `CLOSED` | Circuit Breaker en `OPEN` tras expirar `waitDurationInOpenState` (10s) | Envío de llamadas de prueba exitosas | Pasa a estado `HALF_OPEN`; al responder exitosamente las llamadas de sondeo, el Circuit Breaker se restablece automáticamente a `CLOSED`. |

---

## 5. Guía de Ejecución en PowerShell

### 5.1 Configuración de Variables de Entorno y Conexión

```powershell
# Variables de entorno para prueba
$GATEWAY_URL = "http://127.0.0.1:8081"
$APP_URL     = "http://127.0.0.1:8080"
$TENANT_A    = "11111111-1111-1111-1111-111111111111"
$TENANT_B    = "22222222-2222-2222-2222-222222222222"

# Token Keycloak para invocaciones aseguradas
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

$headersTenantA = @{
    "Authorization" = "Bearer $($tokenResponse.access_token)"
    "X-Tenant-ID"   = $TENANT_A
    "Content-Type"  = "application/json"
}
```

---

### 5.2 Receta: Crear Perfil de Integración Protegido con CredentialRef y Políticas

```powershell
# Crear perfil con referencia a credencial Vault y políticas de Rate Limiting
$profileBody = @{
    businessDomain = "Customer"
    externalSource = "SAP"
    direction = "OUTBOUND"
    sourceOfTruth = "PLATFORM"
    configuration = @{
        protocol = "REST"
        connector = "sap-erp"
        adapter = "sap-customer-outbound-adapter"
        endpoint = "https://sap.corp.internal/api/v1/customers"
        credentialRef = "vault:secret/data/tenants/$TENANT_A/sap"
        rateLimitPolicy = '{"requestsPerUnit": 10, "unit": "SECOND"}'
        retryPolicy = '{"maxAttempts": 3, "backoffMs": 1000}'
    }
} | ConvertTo-Json -Depth 5

$createdProfile = Invoke-RestMethod -Method Post `
  -Uri "$GATEWAY_URL/api/v1/integration-profiles" `
  -Headers $headersTenantA `
  -Body $profileBody

Write-Host "Perfil creado exitosamente con ID:" $createdProfile.id
```

---

### 5.3 Receta: Verificación de Rate Limiting en Redis

```powershell
# Verificar claves y contadores de rate limiting generados en Redis
docker compose exec redis redis-cli KEYS "ratelimit:*"

# Inspeccionar TTL de la clave de cuota para el tenant
docker compose exec redis redis-cli TTL "ratelimit:$TENANT_A:sap-erp"
```

---

### 5.4 Receta: Monitoreo de Métricas de Circuit Breaker y Resiliencia

```powershell
# Consultar métricas de Actuator para Circuit Breakers activos
$actuatorMetrics = Invoke-RestMethod -Method Get `
  -Uri "$APP_URL/actuator/metrics/resilience4j.circuitbreaker.state" `
  -Headers $headersTenantA

Write-Host "Estado de Circuit Breakers:" ($actuatorMetrics | ConvertTo-Json)
```
