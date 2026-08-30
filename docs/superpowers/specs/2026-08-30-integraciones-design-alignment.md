# Diseño: Alinear "Integration Profiles" con Claude Design ("Integraciones")

## Objetivo

Cerrar la brecha identificada en la auditoría entre la implementación actual
de la página accesible desde el navbar "Integration Profiles" y el diseño
de referencia en Claude Design (proyecto `CLocator 2 Integration Profiles`,
archivo `Integration Console.dc.html`). La auditoría encontró 6 diferencias;
este spec cubre las 6.

## Alcance

### 1-4: Copy y UI (sin cambios de backend)

- **Navbar** (`backoffice/apps/shell/src/app/layout/sidebar.component.ts:28`):
  cambiar `{ path: '/integration/profiles', label: 'Integration Profiles', code: 'IP' }`
  a `{ path: '/integration/profiles', label: 'Integraciones', code: 'IX' }`.
- **Título de página compartido**: el diseño usa el mismo `<h1>Integraciones</h1>`
  tanto en la pestaña Profiles como en la de Flows (líneas 191 y 584 del
  diseño). Cambiar `integration-profile-list.component.html` y
  `flow-list.component.html` de `<h1>Integration Profiles</h1>` /
  `<h1>Flows</h1>` a `<h1>Integraciones</h1>` en ambos.
- **Subtítulo**: en `integration-profile-list.component.html`, completar el
  texto a: "Un perfil define cómo un dominio de negocio se acopla a una
  fuente externa. Un flujo compone varios pasos, ramas y destinos sobre
  esas mismas fuentes." (el diseño usa el mismo subtítulo en ambas pestañas
  — se aplica también en `flow-list.component.html`, reemplazando su texto
  actual "Un flujo es un grafo dirigido...").
- **Badge de conteo en la pestaña Flows**: `integration-tabs.component.ts`
  pasa de un link de texto plano a mostrar el total de flujos entre
  paréntesis, ej. `Flows 5`, usando un `<span>` con estilo de badge (fondo
  `var(--surface)`, borde `var(--border)`, monospace, igual que el diseño
  línea 199). El componente necesita inyectar `FlowService` y llamar
  `list()` en `ngOnInit` para obtener el conteo — no existe hoy ningún
  estado de carga para esto; si la llamada falla, el badge simplemente no
  se muestra (no bloquea el resto de la navegación).

Fuera de alcance: el diseño no muestra ningún badge de conteo en la pestaña
"Integration Profiles" misma (solo en "Flows"), así que no se agrega ahí.

### 5: Columna "Última sync" (expone dato de backend ya existente)

El backend ya registra el estado de sincronización por perfil en
`SyncState` (`application/src/main/java/com/cl2/integration/integration/sync/SyncState.java`):
`lastRunStartedAt: Instant`, `lastRunStatus: SyncRunStatus` (`SUCCESS` |
`FAILED` | `CANCELLED`), `lastError: String`. Hoy esto no se expone en el
endpoint de listado de perfiles.

- `IntegrationProfileService.list`/`.get` se enriquecen: por cada
  `IntegrationProfile`, se consulta `syncStateRepository.find(profile.id())`
  (repo ya existente, inyectado como nueva dependencia del servicio) y se
  usa para construir `IntegrationProfileView.lastSyncAt: Instant?` (de
  `SyncState.lastRunStartedAt`, `null` si no hay `SyncState` para ese
  perfil) y para derivar `status` (ver sección 6).
- Se acepta la consulta N+1 (una consulta de `SyncState` por perfil listado)
  sin batching: el volumen de perfiles por tenant en este proyecto es bajo
  (~decenas), y el resto del código de listado ya sigue el mismo patrón
  simple sin optimizar prematuramente (ver cómo `FlowService.countNodes`
  reparse el JSON por cada flow en cada `list()`).
- Frontend: `integration-profile-list.component.html` cambia la columna
  "Actualizado" (que hoy muestra `profile.updatedAt`) por "Última sync"
  (`ÚLTIMA SYNC` en el diseño), mostrando `profile.lastSyncAt` con una
  nueva utilidad de tiempo relativo (`shared/time-ago.pipe.ts`, pipe puro
  `timeAgo`) que formatea a `hace N min/h/d` o `—` si `lastSyncAt` es
  `null`. No existe ninguna utilidad de tiempo relativo en el proyecto hoy
  (`DatePipe` de Angular solo formatea fechas absolutas); es la única pieza
  nueva de infraestructura de UI que este slice agrega, acotada a un solo
  pipe puro sin dependencias.

### 6: Estados enriquecidos del perfil

**Regla de estado derivado** (no se persiste como columna nueva de estado;
se calcula igual que `Flow.status()` se deriva de `activeVersionNumber`/`archived`):

| Condición | `IntegrationProfileStatus` |
|---|---|
| `active == false` | `INACTIVE` |
| `active == true` y `paused == true` | `PAUSED` |
| `active == true`, `paused == false`, sin `SyncState` registrado | `DRAFT` |
| `active == true`, `paused == false`, `SyncState.lastRunStatus == FAILED` | `ERROR` |
| `active == true`, `paused == false`, `SyncState.lastRunStatus == CANCELLED` | `DEGRADED` |
| `active == true`, `paused == false`, `SyncState.lastRunStatus == SUCCESS` | `ACTIVE` |

`DEGRADED` usa `SyncRunStatus.CANCELLED`, un valor que ya existe en el
enum (`application/src/main/java/com/cl2/integration/integration/sync/SyncRunStatus.java`)
pero que hasta ahora no se traducía a ningún estado visible en el
frontend — encaja como la señal de "corrida interrumpida, no un fallo
duro" sin necesidad de telemetría nueva. Esta regla es una decisión de
este spec (el diseño no define de dónde sale "Degradado"; en el mock usa
un dato de ejemplo basado en p95 de latencia, que este proyecto no
registra por perfil — se descarta esa vía explícitamente por falta de esa
métrica, y se opta por `CANCELLED` como señal real y ya disponible).

**Nuevo campo persistido `paused`:**

- `IntegrationProfile` (dominio): agrega campo `paused: boolean` (default
  `false` en `create`), métodos `pause()` y `resume()` (mismo patrón que
  `archive()` en `Flow`: no-op si ya está en ese estado, si no incrementa
  `version` y actualiza `updatedAt`).
- Migración `V15__add_integration_profile_paused.sql`:
  ```sql
  ALTER TABLE integration_profile
      ADD COLUMN paused BOOLEAN NOT NULL DEFAULT FALSE;
  ```
- `IntegrationProfileJpaEntity`: agrega columna `paused`.
- `IntegrationProfilePersistenceAdapter`: el `update` optimista existente
  (columna por columna) agrega `paused` a la lista de columnas que
  actualiza.

**Nuevos endpoints** (`IntegrationProfileController`, mismo estilo que
`FlowController.publish`/`.rollback`):

- `POST /api/v1/integration-profiles/{profileId}/pause` → `200` con
  `IntegrationProfileResponse` actualizado.
- `POST /api/v1/integration-profiles/{profileId}/resume` → `200` ídem.

**`IntegrationProfileView`/`IntegrationProfileResponse`** agregan:
`paused: boolean`, `status: IntegrationProfileStatus` (serializado como
string), `lastSyncAt: Instant?`.

**Frontend:**

- `integration-profile.model.ts`: agrega `IntegrationProfileStatus` (union
  type con los 6 valores), `paused: boolean`, `status: IntegrationProfileStatus`,
  `lastSyncAt: string | null` a `IntegrationProfile`.
- `integration-profile.service.ts`: agrega `pause(id)`/`resume(id)`.
- `integration-profile-list.component.ts`/`.html`: la columna "Estado"
  pasa de un badge binario (`Activo`/`Inactivo`) a reflejar los 6 estados,
  con clases de color: `ACTIVE`→ok(verde), `PAUSED`/`DRAFT`/`INACTIVE`→
  muted(gris), `ERROR`→err(rojo), `DEGRADED`→warn(ámbar) — mismos tokens
  de color que ya define `Integration Console.dc.html` (`--ok`, `--warn`,
  `--err`) y que el backoffice ya expone como variables CSS (confirmado:
  `--ok`/`--warn`/`--err` ya están declaradas en `apps/shell/src/styles.css`
  y `apps/integration-mfe/src/styles.css`).
- `integration-profile-detail.component.ts`/`.html`: agrega botón
  "Pausar"/"Reanudar" (según `paused`) junto a las acciones existentes,
  llamando a `service.pause()`/`.resume()` y refrescando el detalle.
- `dashboard-page.component.ts`: la sección "Perfiles que requieren
  atención" (`attention` computed) pasa de filtrar solo `!p.active` a
  incluir perfiles con `status` en `PAUSED`, `ERROR`, o `DEGRADED` (no
  `DRAFT`, que es un estado normal de "recién creado", no una alerta). El
  KPI "PERFILES INACTIVOS" se mantiene igual (sigue contando `!active`,
  que es un concepto distinto de "requiere atención").

Fuera de alcance explícito: no se agrega ninguna métrica de latencia/p95
por perfil (eso pertenece, si se llega a necesitar, a un slice de
observabilidad separado — el diseño la usa solo como dato de ejemplo, sin
que exista una fuente real de esa métrica en este backend).

## Pruebas

- **Dominio** (`IntegrationProfileTest`, si no existe crear el archivo):
  `pause()`/`resume()` incrementan versión y son no-op si ya están en ese
  estado; `create()` arranca con `paused = false`.
- **Aplicación** (`IntegrationProfileServiceTest`): `toView` deriva
  correctamente los 6 estados a partir de combinaciones de
  `active`/`paused`/`SyncState` ausente/`SUCCESS`/`FAILED`/`CANCELLED`;
  aislamiento por tenant.
- **Persistencia** (`IntegrationProfilePersistenceAdapterTest`,
  Testcontainers/MySQL real vía `docker compose up -d mysql`): el campo
  `paused` persiste y se lee correctamente tras `save`.
- **Web** (`IntegrationProfileControllerTest`): `POST .../pause` y
  `.../resume` devuelven `200` con el campo `paused` actualizado; `404` si
  el perfil no existe.
- **Frontend**: specs de `integration-profile.service.ts` (nuevos
  métodos), `integration-profile-list.component` (columna Última sync
  formateada, badge de estado por cada uno de los 6 valores),
  `integration-profile-detail.component` (botón pausar/reanudar),
  `integration-tabs.component` (badge de conteo de flows, incluyendo el
  caso sin conteo si falla la llamada), `dashboard-page.component`
  (atención incluye perfiles pausados/con error/degradados), y un test
  nuevo para el pipe `timeAgo` (casos: segundos, minutos, horas, días,
  `null`).
