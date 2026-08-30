# Diseño: Flow CRUD y versionado (slice 1 del motor de Flows)

## Objetivo

Dar soporte real al primer sub-proyecto del motor de Flows: crear, listar,
editar y versionar flujos (grafo de nodos/edges) desde el backoffice. Este
slice **no** incluye el motor de ejecución ni el tracking de corridas — esas
quedan para slices posteriores. El resultado habilita el botón "＋ Nuevo
flujo" y el Designer del backoffice (`backoffice/apps/integration-mfe`), que
hoy muestran el estado "no disponible" porque no existe API de flows.

## Alcance

Sigue el mismo patrón hexagonal que `IntegrationProfile`
(`application/src/main/java/com/cl2/integration/{domain,application,adapter}`):
dominio inmutable con optimistic locking, puerto de repositorio, adapter JPA,
controller REST, migración Flyway.

### Modelo de dominio

`Flow` (agregado mutable, análogo a `IntegrationProfile`):

| Campo | Tipo | Regla |
|---|---|---|
| `id` | UUID | generado al crear |
| `tenantId` | UUID | de `TenantContext`, nunca desde el cliente |
| `code` | string | único por tenant; slug tipo `flow/vehiculo-alta` |
| `name` | string | no vacío |
| `draftGraph` | JSON nullable | `{ "nodes": [...], "edges": [...] }`, opaco — el backend no valida su estructura interna en este slice |
| `triggerSummary` | string nullable | texto libre, ej. `CRON */5`, `WEBHOOK`; no derivado del grafo |
| `activeVersionNumber` | int nullable | `null` hasta el primer publish |
| `archived` | boolean | soft-delete, igual que `active` en `IntegrationProfile` (invertido) |
| `createdAt` / `updatedAt` | Instant | |
| `version` | long | optimistic locking, igual que `IntegrationProfile` |

`FlowVersion` (inmutable, un snapshot congelado por publish):

| Campo | Tipo |
|---|---|
| `id` | UUID |
| `flowId` | UUID |
| `tenantId` | UUID |
| `versionNumber` | int (secuencial por flow, arranca en 1) |
| `graph` | JSON (copia congelada de `draftGraph` al momento del publish) |
| `state` | enum `ACTIVE` \| `PUBLISHED` \| `ROLLED_BACK` |
| `publishedBy` | string (subject/email del JWT) |
| `publishedAt` | Instant |

Reglas de `state`: exactamente una `FlowVersion` por flow puede estar
`ACTIVE`. Al publicar una versión nueva, la que era `ACTIVE` pasa a
`PUBLISHED`. Al hacer rollback a una versión anterior, esa pasa a `ACTIVE` y
la que era `ACTIVE` pasa a `ROLLED_BACK`. `PUBLISHED` y `ROLLED_BACK` son
estados terminales de auditoría — nunca se sobrescriben salvo por esta
transición de vuelta a `ACTIVE` en un rollback.

Status derivado del flow (para la lista, no persistido):

- `DRAFT` — `activeVersionNumber == null`
- `PUBLISHED` — `activeVersionNumber != null`
- `OBSOLETE` — `archived == true` (tiene prioridad sobre los anteriores)

`nodeCount` (para la lista) se deriva contando `draftGraph.nodes` en el
adapter de persistencia o en el servicio de aplicación al mapear a la vista;
no se persiste como columna separada.

## Contrato HTTP

`RestController` en `/api/v1/flows`, mismo estilo que
`IntegrationProfileController`. Todas las operaciones usan
`TenantContext.requireTenantId()`; ningún payload puede establecer el tenant.

- `POST /api/v1/flows` — body `{ code, name }` → crea Flow en DRAFT, `draftGraph: null`. `201 Created`.
- `GET /api/v1/flows?archivedOnly=false` — lista (sin `draftGraph` completo, solo resumen: id, code, name, status, activeVersionNumber, nodeCount, triggerSummary, updatedAt).
- `GET /api/v1/flows/{flowId}` — detalle completo, incluye `draftGraph`.
- `PUT /api/v1/flows/{flowId}` — body `{ name, triggerSummary, draftGraph, expectedVersion }` → actualiza el draft. Conflicto de versión → `409` (mismo `IntegrationProfileConflictException`, se crea el análogo `FlowConflictException`).
- `GET /api/v1/flows/{flowId}/versions` — historial completo (todas las `FlowVersion`, más reciente primero).
- `POST /api/v1/flows/{flowId}/versions/publish` — congela `draftGraph` actual como nueva `FlowVersion` `ACTIVE`; requiere que `draftGraph` no sea `null`/vacío (`422` si lo es). `201 Created`.
- `POST /api/v1/flows/{flowId}/versions/{versionNumber}/rollback` — reactiva una versión previa. `404` si `versionNumber` no existe para ese flow.
- `DELETE /api/v1/flows/{flowId}` — `archived = true`. `204 No Content`. Igual que profiles, no borra físicamente.

Fuera de alcance explícito para este slice:

- **Enforcement de rol `flow:publisher`** en el backend. Keycloak no tiene
  roles granulares mapeados todavía en este proyecto (solo `tenant_id`); el
  mock lo simula en el cliente, pero replicarlo en el backend requeriría
  diseñar el mapeo de client roles de Keycloak → `Authentication` de Spring
  Security, que es un cambio transversal de seguridad, no específico de
  Flows. Publish/rollback quedan abiertos a cualquier usuario autenticado del
  tenant, igual que hoy `PUT`/`DELETE` de integration-profiles.
- **Métricas de ejecución** (`execCount24h`, `errorRate`, `p95`, ejecuciones,
  DLQ por paso): pertenecen al slice #3 (tracking de ejecuciones). El modelo
  de `Flow` de este slice no las incluye.
- **Validación estructural del grafo** (tipos de nodo válidos, edges sin
  huérfanos, ciclos): el motor de ejecución (slice #2) es quien le da
  semántica al grafo; aquí se persiste y versiona como JSON opaco.

## Persistencia

Dos migraciones Flyway nuevas, continuando la numeración (`V10` es la
última existente):

- `V11__create_flow.sql` — tabla `flow` con las columnas del dominio,
  `UNIQUE (tenant_id, code)`, `draft_graph JSON NULL`.
- `V12__create_flow_version.sql` — tabla `flow_version` con FK a `flow`,
  `UNIQUE (flow_id, version_number)`, `graph JSON NOT NULL`.

El adapter de persistencia (`FlowPersistenceAdapter`) sigue el mismo patrón
`updateIfVersionMatches` que `IntegrationProfilePersistenceAdapter` para el
`PUT` del draft. `publish`/`rollback` se ejecutan dentro de una transacción
que actualiza dos filas de `flow_version` (la saliente y la entrante) más
`flow.active_version_number`, protegido por el mismo optimistic lock de
`flow.version` para evitar publish/rollback concurrentes.

## Seguridad y multitenancy

Igual que `IntegrationProfile`: tenant desde `TenantContext` (claim
`tenant_id` del JWT propagado por el Gateway), nunca desde el cliente.
`draftGraph`/`graph` son JSON de configuración del propio tenant — no se
espera que contengan secretos, pero se tratan con la misma disciplina que
`mapping`/`transformation` de `IntegrationProfileConfiguration` (no se
registran en logs de autenticación).

## BFF y frontend

`GatewayProxyController`/`GatewayProxyService` (`backoffice/apps/bff`) suman
los métodos análogos a los de integration-profiles: `getFlows`, `getFlow`,
`createFlow`, `updateFlow`, `listFlowVersions`, `publishFlow`,
`rollbackFlow`, `deleteFlow` (mismo guard `SessionAccessTokenGuard`).

En `backoffice/apps/integration-mfe/src/app/flow/`:

- `flow.model.ts` — se ajusta a la forma real del contrato: se quitan
  `execCount24h`, `errorRate`, `p95` (no existen en este slice); se agregan
  `activeVersionNumber`, `archived`, `draftGraph`, `FlowVersion`.
- `flow.service.ts` — se agregan los métodos correspondientes a los nuevos
  endpoints.
- `flow-list.component.*` — se habilita "＋ Nuevo flujo" (abre un form
  simple: code + name, `POST`, navega al designer del flow creado).
- `flow-designer.component.*` — reemplaza el placeholder por un formulario
  funcional: nombre, trigger, un `<textarea>` con el JSON del grafo (edición
  directa, sin canvas todavía — consistente con "JSON opaco" del backend),
  botón "Guardar cambios" (`PUT`), "Publicar" (deshabilitado si el draft está
  vacío), e historial de versiones con "Rollback" por fila.
- `flow-executions.component.*` / `flow-execution-detail.component.*` — sin
  cambios; siguen mostrando "no disponible" hasta el slice #3.

## Pruebas

Antes de implementar, se agregan pruebas para:

- **Dominio** (`FlowTest`): creación con `version=0`, `update` incrementa
  versión y respeta `expectedVersion`, transición de estado derivado
  (DRAFT → PUBLISHED al fijar `activeVersionNumber`, → OBSOLETE al archivar).
- **Aplicación** (`FlowServiceTest`): create rechaza `code` duplicado activo
  en el tenant; publish sin `draftGraph` falla; publish mueve la versión
  `ACTIVE` anterior a `PUBLISHED`; rollback mueve la `ACTIVE` actual a
  `ROLLED_BACK` y reactiva la versión objetivo; aislamiento por tenant en
  todas las operaciones.
- **Persistencia** (`FlowPersistenceAdapterTest`, Testcontainers MySQL):
  optimistic locking del draft (`409` en conflicto), unicidad de `code` por
  tenant, persistencia/lectura de JSON del grafo, unicidad de
  `(flow_id, version_number)`.
- **Web** (`FlowControllerTest`): contrato HTTP de cada endpoint, incluidos
  los códigos de error (`404`, `409`, `422`).
- **Frontend**: specs de `flow.service.ts` (nuevos métodos), `flow-list`
  (crear flujo, navegar al designer), `flow-designer` (guardar draft,
  publicar, listar versiones, rollback) — mismo estilo que los specs
  existentes de `integration-profile-*`.
