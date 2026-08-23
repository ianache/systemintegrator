# Diseño: Backoffice Administrativo — Arquitectura MicroUI (Shell + BFF + Keycloak)

## Objetivo

Definir la arquitectura de un portal de administración ("Backoffice") para la Plataforma
de Integración, compuesto por un Shell y un conjunto de Micro Frontends (MicroUIs),
iniciando con el MicroUI "integration" (consola de administración de
`IntegrationProfile`, monitor de mensajes Outbox/Inbox/DLQ, conectores y credenciales).
El portal se apoya en un Backend-for-Frontend (BFF) en NodeJS que centraliza la
seguridad vía Keycloak y reutiliza el Gateway y los microservicios existentes del
proyecto `gateway`/`app`.

## Alcance

- Arquitectura del Shell y del primer MicroUI ("integration"), y del BFF que los sirve.
- Modelo de autenticación/sesión del Backoffice contra Keycloak (realm `Apps`).
- Extensión del Gateway existente para confiar también en el realm `Apps`.
- Estructura de repositorio/workspace y topología de despliegue en `compose.yaml`.
- Mapeo del alcance funcional del MicroUI "integration" (validado contra el diseño UX
  "Integration Console" del proyecto claude-design) a los endpoints ya existentes.

## Fuera de alcance

- Cambio de tenant en caliente para un mismo admin (queda para una fase futura; ver ADR-0004).
- Implementación de endpoints backend nuevos para "Conectores y adapters" y
  "Credenciales" si no existen ya (se identifica como brecha, no se resuelve aquí).
- MicroUIs adicionales más allá de "integration".
- Configuración operativa real del realm `Apps` en Keycloak (se asume su existencia
  como estándar organizacional; este diseño solo define el contrato de claims que
  necesita emitir).

## Contexto relevante del sistema existente

- El Gateway (`gateway/`, Spring Cloud Gateway WebFlux) valida JWT vía Spring Security
  OAuth2 Resource Server contra un único issuer, hoy
  `https://oauth2.qa.comsatel.com.pe/realms/microservicios`
  (`gateway/src/main/resources/application.yml`, `GatewaySecurityConfig`).
- `TenantClaimGatewayFilter` deriva el tenant **exclusivamente** del claim `tenant_id`
  del JWT validado, y sobrescribe cualquier `X-Tenant-ID` enviado por el cliente. Este
  invariante de seguridad no cambia con este diseño — solo se amplía la lista de
  issuers confiables.
- El Gateway enruta `/api/**` hacia `app:8080` dentro de Compose, publicando único
  puerto host `8081`.
- El diseño UX "Integration Console" (proyecto claude-design
  `7f62b059-0b32-4671-967b-f7a810fd6ef4`, archivo `Integration Console.dc.html`) define
  las pantallas reales que el MicroUI "integration" debe implementar: Dashboard,
  Integration Profiles (listado + wizard + detalle con 5 tabs), Monitor de mensajes
  (Outbox/Inbox + DLQ), Conectores y adapters, Credenciales.

## Arquitectura y flujo de request

```text
Browser (Angular Shell + MicroUI "integration", Native Federation)
   │  1. GET /  → BFF sirve el Shell (host) estático
   │  2. Login → redirige a BFF /auth/login
   ▼
BFF (NodeJS + NestJS) — puerto propio (ej. :4000), ingreso público independiente
   │  - Ejecuta Authorization Code + PKCE contra Keycloak realm "Apps" (cliente confidencial)
   │  - Guarda tokens (access/refresh) SOLO server-side; cookie de sesión
   │    HttpOnly + Secure + SameSite hacia el browser (patrón BFF Session)
   │  - Expone /bff/api/**: recibe llamadas del Shell/MicroUI autenticadas por cookie
   │  - Adjunta `Authorization: Bearer <access_token del admin>` y reenvía al Gateway
   ▼
Gateway (Spring Cloud Gateway, YA EXISTE) — http://middleware:8081
   │  - Extendido a MULTI-ISSUER: confía en realms/microservicios (tráfico actual)
   │    Y realms/Apps (admins Backoffice)
   │  - TenantClaimGatewayFilter sigue igual: deriva tenant_id SOLO del JWT validado
   │  - Enruta /api/** → app:8080 (sin cambios)
   ▼
Integration Application (Spring Boot, YA EXISTE)
```

Puntos clave:

- El navegador nunca ve un access/refresh token — solo la cookie de sesión opaca del
  BFF. Esto es lo que hace al BFF el guardián de seguridad real, no solo un proxy.
- El Gateway no cambia su invariante central (tenant siempre derivado de un JWT
  validado, nunca de un header del cliente) — solo amplía los issuers confiables.
- El realm `Apps` necesita un protocol mapper que emita `tenant_id` para los usuarios
  admin del Backoffice, con el mismo contrato de claim que ya usa `microservicios`.

## Modelo de tenant (v1)

Cada admin del Backoffice opera sobre un único tenant fijo por sesión, determinado por
el claim `tenant_id` de su JWT (realm `Apps`). No hay cambio de tenant en caliente en
esta fase: el selector de tenant del diseño UX queda deshabilitado o limitado a un solo
tenant disponible hasta que se apruebe una fase futura con su propio modelo de
confianza (ver ADR-0004).

## Estructura del workspace y despliegue

**Nx workspace** en `backoffice/` (hermano de `gateway/` en el mismo repositorio):

```text
backoffice/
  apps/
    shell/              # Angular Shell (host), Native Federation, routing raíz, layout
    integration-mfe/     # MicroUI "integration" (remote)
    bff/                 # NestJS: sesión OIDC, proxy autenticado hacia el Gateway
  libs/
    shared-ui/           # design tokens y componentes compartidos
    shell-contracts/      # contrato TS Shell↔MicroUI (rutas expuestas, tipos de manifest)
```

**Despliegue (`compose.yaml`)** — nuevos servicios junto a
`mysql/redis/kafka/app/middleware/prometheus/grafana`:

- `backoffice-bff` — imagen Node/NestJS, puerto publicado propio (ej. `4000`), env
  `KEYCLOAK_APPS_ISSUER_URI`, `GATEWAY_URI=http://middleware:8081`, secretos de
  sesión/cliente OIDC vía `.env` (nunca versionados, mismo patrón que
  `KEYCLOAK_ISSUER_URI` hoy).
- `backoffice-shell` — build estático (Shell + MicroUI remotes) servido por Nginx.
- Healthcheck del BFF (`/health`), siguiendo el mismo patrón que `middleware`
  (`/actuator/health`).

## Alcance funcional del MicroUI "integration"

| Vista | Backend consumido |
|---|---|
| Dashboard (KPIs, perfiles con atención) | agregación de `GET /api/v1/integration-profiles` + métricas |
| Integration Profiles (listado, wizard, detalle, tabs) | `GET/POST/PUT /api/v1/integration-profiles` |
| Mapping & Transformation + dry-run | endpoints del motor de transformación existente |
| Monitor de mensajes (Outbox/Inbox, DLQ) | endpoints de outbox/inbox + DLQ replay service (commit `6879724`) |
| Conectores y adapters | **brecha identificada**: no existe endpoint dedicado hoy; se resuelve en el plan de implementación |
| Credenciales | **brecha identificada**: solo existe `credentialRef` embebido en `IntegrationProfile`; se resuelve en el plan de implementación |

## Manejo de errores

- Sesión expirada en el BFF → redirect a `/auth/login` preservando la URL de retorno.
  El Shell nunca reintenta con un token guardado en el navegador.
- 401/403 del Gateway → el BFF los traduce a una respuesta de error estructurada; el
  Shell muestra el estado correspondiente sin filtrar detalles del token.
- Fallo al cargar un remote (MicroUI caída) → el Shell aísla el error con un boundary
  por ruta; no tumba el resto de la consola.

## Pruebas

- BFF: tests unitarios de callback OIDC, middleware de sesión y proxy autenticado;
  tests de contrato contra el Gateway usando JWKS local (mismo patrón que usan hoy los
  tests deterministas del Gateway).
- Gateway: extensión de sus tests deterministas existentes para cubrir resolución
  multi-issuer (ambos issuers aceptados, `tenant_id` exigido desde cualquiera).
- Shell/MicroUI: tests de componente + e2e del flujo de login contra un realm/mock OIDC
  de prueba.

## Criterios de aceptación

- El Shell carga el MicroUI "integration" vía Native Federation sin acoplar sus builds.
- Un admin autenticado en el realm `Apps` completa el login vía BFF sin que el
  navegador reciba jamás un access/refresh token.
- El Gateway acepta JWT válidos de `realms/microservicios` y `realms/Apps`, y sigue
  rechazando cualquier `X-Tenant-ID` no derivado del JWT.
- `docker compose config --quiet` valida el `compose.yaml` extendido sin secretos
  versionados.
- Los 7 ADRs listados en este diseño quedan documentados en `docs/adrs/`.

## ADRs derivados

1. `0001-microui-architecture-shell-native-federation.md`
2. `0002-bff-session-pattern-nodejs-nestjs.md`
3. `0003-gateway-multi-issuer-trust-realm-apps.md`
4. `0004-backoffice-tenant-model-v1-fixed-tenant.md`
5. `0005-backoffice-nx-monorepo-workspace.md`
6. `0006-backoffice-deployment-topology.md`
7. `0007-bff-nodejs-framework-nestjs.md`
