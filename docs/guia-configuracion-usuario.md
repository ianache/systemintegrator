# Guía paso a paso: configurar un usuario para acceder al API

## 1. Objetivo

Configurar un usuario del realm `microservicios` para obtener un access token OAuth2 desde Keycloak QA y consumir el API a través del Gateway local.

Flujo validado:

```text
Usuario → Keycloak QA → Bearer JWT → Gateway :8081 → App → MySQL/Kafka
```

Datos del entorno:

| Elemento | Valor |
|---|---|
| Keycloak | `https://oauth2.qa.comsatel.com.pe` |
| Realm | `microservicios` |
| Issuer | `https://oauth2.qa.comsatel.com.pe/realms/microservicios` |
| Token endpoint | `<issuer>/protocol/openid-connect/token` |
| Gateway | `http://127.0.0.1:8081` |
| Claim obligatorio | `tenant_id` con formato UUID |

No guardar contraseñas, tokens ni headers `Authorization` en Git, archivos `.env`, logs o evidencias.

## 2. Crear o verificar el usuario en Keycloak

1. Abrir la consola administrativa de Keycloak QA.
2. Seleccionar el realm **`microservicios`**. No realizar estos pasos en `master`.
3. Ir a **Users** → **Add user**.
4. Crear el usuario, por ejemplo:

   - Username: `integracion`
   - Email: correo QA del equipo
   - Email verified: según la política del entorno
   - Enabled: `On`

5. Guardar.
6. Abrir la pestaña **Credentials**.
7. Definir una contraseña temporal para la prueba.
8. Desactivar **Temporary** para que el password grant no sea rechazado por cambio obligatorio.

Si el usuario ya existe, verificar que esté habilitado y restablecer la contraseña si se recibe `invalid_grant`.

> Una cuenta administrativa del realm `master` no es automáticamente un usuario del realm `microservicios`. El usuario debe existir en `microservicios`.

## 3. Asignar el tenant al usuario

El Gateway no acepta el tenant enviado por el cliente. Obtiene el tenant exclusivamente del claim `tenant_id` del JWT.

1. En el usuario `integracion`, abrir la pestaña **Attributes**.
2. Crear el atributo:

   | Name | Value |
   |---|---|
   | `tenant_id` | `11111111-1111-1111-1111-111111111111` |

3. Guardar.

Usar otro UUID para un segundo usuario/tenant, por ejemplo:

```text
22222222-2222-2222-2222-222222222222
```

## 4. Crear el cliente OAuth2 para pruebas manuales

Se recomienda crear un cliente separado para pruebas manuales en lugar de reutilizar `admin-cli`.

1. En el realm `microservicios`, ir a **Clients** → **Create client**.
2. Configurar:

   - Client type: `OpenID Connect`
   - Client ID: `integration-manual`
   - Name: `Integration Manual Tests`
   - Client authentication: `Off` para cliente público de pruebas locales
   - Authorization: `Off`
   - Standard flow: puede quedar `Off`
   - Direct access grants: `On`

3. Guardar el cliente.
4. No crear ni versionar un client secret para este flujo público.

Si la política del realm exige cliente confidencial, activar **Client authentication**, generar el secret y proporcionarlo únicamente mediante una variable de entorno local `KEYCLOAK_CLIENT_SECRET`.

## 5. Crear el mapper `tenant_id`

El atributo del usuario no aparece automáticamente en el access token. Debe agregarse mediante un Protocol Mapper.

### Opción recomendada: mapper dedicado del cliente

1. Abrir **Clients** → `integration-manual`.
2. Ir a **Client scopes**.
3. Abrir **Dedicated scopes** del cliente.
4. Seleccionar **Configure a new mapper** → **By configuration** → **User Attribute**.
5. Configurar:

   | Campo | Valor |
   |---|---|
   | Name | `tenant_id` |
   | User Attribute | `tenant_id` |
   | Token Claim Name | `tenant_id` |
   | Claim JSON Type | `String` |
   | Add to ID token | opcional |
   | Add to access token | `On` |
   | Add to userinfo | opcional |

6. Guardar.

El API solo necesita el claim en el **access token**.

## 6. Levantar el entorno local con perfil QA

Desde PowerShell, en la raíz del repositorio:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'qa-e2e'
$env:KEYCLOAK_ISSUER_URI = 'https://oauth2.qa.comsatel.com.pe/realms/microservicios'

docker compose config --quiet
docker compose up -d --build mysql redis kafka app middleware
docker compose ps
```

Esperado: `mysql`, `redis`, `kafka`, `app` y `middleware` en estado `healthy`.

Verificar el Gateway:

```powershell
curl.exe -i http://127.0.0.1:8081/actuator/health
```

Esperado: HTTP `200` y `{"status":"UP"}`.

## 7. Solicitar el access token

Definir la contraseña solo en memoria durante la sesión:

```powershell
$env:KEYCLOAK_CLIENT_ID = 'integration-manual'
$env:KEYCLOAK_USERNAME = 'integracion'
$securePassword = Read-Host 'Keycloak password' -AsSecureString
$env:KEYCLOAK_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password

$issuer = $env:KEYCLOAK_ISSUER_URI
$tokenResponse = Invoke-RestMethod `
  -Method Post `
  -Uri "$issuer/protocol/openid-connect/token" `
  -ContentType 'application/x-www-form-urlencoded' `
  -Body @{
    grant_type = 'password'
    client_id = $env:KEYCLOAK_CLIENT_ID
    username = $env:KEYCLOAK_USERNAME
    password = $env:KEYCLOAK_PASSWORD
  }

$env:ACCESS_TOKEN = $tokenResponse.access_token
```

No ejecutar `$tokenResponse` ni `Write-Host $env:ACCESS_TOKEN`, porque imprimiría el token.

Si el cliente es confidencial, incluir:

```powershell
client_secret = $env:KEYCLOAK_CLIENT_SECRET
```

## 8. Verificar que el JWT tenga `tenant_id`

El siguiente comando muestra únicamente claims seleccionados; no muestra el token completo:

```powershell
$parts = $env:ACCESS_TOKEN.Split('.')
$payload = $parts[1].Replace('-', '+').Replace('_', '/')
while ($payload.Length % 4) { $payload += '=' }
$claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json

[PSCustomObject]@{
  issuer = $claims.iss
  username = $claims.preferred_username
  tenant_id = $claims.tenant_id
}
```

Esperado:

```text
issuer   : https://oauth2.qa.comsatel.com.pe/realms/microservicios
tenant_id: 11111111-1111-1111-1111-111111111111
```

Si `tenant_id` está vacío, revisar el atributo y el mapper. El Gateway responderá `403` aunque el usuario y la contraseña sean correctos.

## 9. Probar el API a través del Gateway

### 9.1 Health público

```powershell
curl.exe -i http://127.0.0.1:8081/actuator/health
```

Esperado: `200`.

### 9.2 Listar perfiles

```powershell
curl.exe -i http://127.0.0.1:8081/api/v1/integration-profiles `
  -H "Authorization: Bearer $env:ACCESS_TOKEN"
```

Esperado: `200` y datos únicamente del tenant del claim.

### 9.3 Crear un perfil

```powershell
curl.exe -i -X POST http://127.0.0.1:8081/api/v1/integration-profiles `
  -H "Authorization: Bearer $env:ACCESS_TOKEN" `
  -H 'Content-Type: application/json' `
  -H 'X-Tenant-ID: 22222222-2222-2222-2222-222222222222' `
  --data '{"businessDomain":"orders","externalSource":"erp","syncDirection":"INBOUND","sourceOfTruth":"PLATFORM"}'
```

Esperado: `201`. El header falso debe ser ignorado; la respuesta debe mostrar el tenant del JWT.

### 9.4 Validar aislamiento

Crear un segundo usuario con otro `tenant_id`, obtener un segundo token y consultar el `PROFILE_ID` creado con el primer usuario.

Esperado: `404` o la respuesta de aislamiento definida por el contrato; nunca debe devolver el perfil del otro tenant.

## 10. Errores frecuentes

### `invalid_grant` / `Invalid user credentials`

- El usuario no existe en `microservicios`.
- Se está usando un usuario del realm `master` contra `microservicios`.
- La contraseña es incorrecta o sigue marcada como temporal.
- El usuario está deshabilitado.

### `unauthorized_client`

- El cliente no tiene habilitado **Direct access grants**.
- El `client_id` no existe en el realm.
- Falta `client_secret` para un cliente confidencial.

### Token correcto pero Gateway devuelve `403`

- El JWT no contiene `tenant_id`.
- El claim no tiene formato UUID.
- El mapper no está incluido en el access token.

### Gateway devuelve `401`

- El issuer del token no coincide con `KEYCLOAK_ISSUER_URI`.
- El token expiró.
- La firma/JWK no puede validarse.
- El header debe tener exactamente el formato `Authorization: Bearer <token>`.

### PowerShell no puede conectar a Keycloak

Si el host no tiene salida HTTPS al dominio QA, solicitar el token desde el contenedor middleware, sin mostrarlo:

```powershell
$response = docker compose exec -T middleware sh -c "curl -skS -X POST https://oauth2.qa.comsatel.com.pe/realms/microservicios/protocol/openid-connect/token -H 'Content-Type: application/x-www-form-urlencoded' --data 'grant_type=password&client_id=integration-manual&username=integracion&password=<PASSWORD>'"
```

No guardar `$response` en archivos ni imprimirlo. La contraseña debe reemplazarse en la sesión local y no debe quedar en el historial compartido.

## 11. Limpiar credenciales de la sesión

```powershell
Remove-Item Env:ACCESS_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:KEYCLOAK_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:KEYCLOAK_USERNAME -ErrorAction SilentlyContinue
Remove-Item Env:KEYCLOAK_CLIENT_ID -ErrorAction SilentlyContinue
```

Detener el entorno conservando datos:

```powershell
docker compose down --remove-orphans
```
