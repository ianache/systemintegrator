# Diseño: Tracking de ejecuciones y métricas de Flows (slice 3)

## Objetivo

Cerrar el gap identificado en el tab "Flows" del backoffice: los 4 cards de
KPIs ("Flujos publicados", "Ejecuciones 24h", "Tasa de error", "P95 por
ejecución") no existen porque no hay ningún registro de ejecuciones de
flujos en el sistema — hoy `Flow`/`FlowVersion` (slice 1, ver
[2026-08-30-flow-crud-versioning-design.md](2026-08-30-flow-crud-versioning-design.md))
solo cubren definición y versionado, nunca ejecución.

Este slice **no** construye un motor que corra flujos (slice 2, fuera de
alcance). Construye el modelo de datos y la API para que un futuro motor de
ejecución (interno o un adaptador externo) *reporte* ejecuciones vía API, y
expone un endpoint de métricas agregadas que el frontend consume para
poblar los cards y (más adelante) `flow-executions.component`.

## Alcance

Mismo patrón hexagonal que `Flow`
(`application/src/main/java/com/cl2/integration/{domain,application,adapter}`):
dominio inmutable, puerto de repositorio, adapter JPA, controller REST,
migración Flyway.

### Modelo de dominio

`FlowExecution` — agregado inmutable, de solo-inserción (no tiene
transiciones de estado como `Flow`; una ejecución reportada es un hecho
cerrado):

| Campo | Tipo | Regla |
|---|---|---|
| `id` | UUID | generado al crear |
| `tenantId` | UUID | de `TenantContext`, nunca desde el cliente |
| `flowId` | UUID | debe existir un `Flow` con ese id en el tenant (`404` si no) |
| `flowVersionNumber` | int | versión del flow que corrió; no se valida contra `FlowVersion` en este slice (el reportante es responsable de la exactitud) |
| `status` | enum `SUCCESS` \| `FAILURE` | |
| `startedAt` | Instant | provisto por el reportante (no `Instant.now()` del backend), para no perder precisión si el reporte llega con delay |
| `finishedAt` | Instant | debe ser `>= startedAt` (`422` si no) |
| `durationMs` | long | derivado en el dominio como `finishedAt - startedAt` al construir, no confiar en un valor enviado por el cliente |
| `errorMessage` | string nullable | solo tiene sentido si `status == FAILURE`; se persiste igual si viene en `SUCCESS` (no se rechaza, simplemente no se usa en métricas) |

No hay agregado `FlowExecutionVersion` ni estado editable: `FlowExecution`
se crea una vez (`FlowExecution.report(...)`) y no se actualiza ni se
borra. Esto lo diferencia de `Flow`/`FlowVersion`, que sí llevan optimistic
locking — aquí no aplica.

### Métricas agregadas

`FlowMetricsSummary` — vista de aplicación (no persistida), calculada on
demand por tenant:

| Campo | Cálculo |
|---|---|
| `publishedFlowCount` | `COUNT(*)` sobre `flow` donde `active_version_number IS NOT NULL AND archived = false`, por tenant (reutiliza `FlowRepository`, no toca `flow_execution`) |
| `executions24h` | `COUNT(*)` sobre `flow_execution` donde `tenant_id = ? AND started_at >= now() - INTERVAL 24 HOUR` |
| `errorRatePct` | `100.0 * COUNT(status = FAILURE) / COUNT(*)` sobre la misma ventana de 24h; `0.0` si `executions24h == 0` (evita división por cero, no `null`) |
| `p95DurationMs` | percentil 95 de `duration_ms` sobre la misma ventana de 24h; `null` si `executions24h == 0` |

**Cálculo de p95 sin `PERCENTILE_CONT`:** MySQL 8.4 (el motor de este
proyecto, ver `compose.yaml`) no garantiza soporte estable de funciones de
percentil en todas las configuraciones. En vez de depender de eso, el
adapter de persistencia calcula el percentil con dos queries simples:

1. `SELECT COUNT(*) FROM flow_execution WHERE tenant_id = ? AND started_at >= ?`
   → `n`.
2. Si `n > 0`: `SELECT duration_ms FROM flow_execution WHERE tenant_id = ? AND started_at >= ? ORDER BY duration_ms ASC LIMIT 1 OFFSET ?`
   con `OFFSET = ceil(n * 0.95) - 1` (clamp a `n - 1` si el redondeo se pasa).

Esto evita funciones de ventana específicas del motor y es eficiente con el
índice `(tenant_id, started_at, duration_ms)` propuesto abajo, sin traer
filas de más a memoria de aplicación.

## Contrato HTTP

Extiende `FlowController` (`/api/v1/flows`), mismo estilo que el resto:

- `POST /api/v1/flows/{flowId}/executions` — body
  `{ flowVersionNumber, status, startedAt, finishedAt, errorMessage? }` →
  `201 Created` con el `FlowExecutionResponse` creado. `404` si `flowId` no
  existe en el tenant. `422` si `finishedAt < startedAt`. Protegido igual
  que el resto de endpoints de flows (JWT + `tenant_id`); no hay un rol
  distinto para "reportar ejecución" en este slice — cualquier cliente
  autenticado del tenant puede reportar, igual que hoy cualquier usuario
  autenticado puede publicar/hacer rollback de un flow.
- `GET /api/v1/flows/metrics/summary` — devuelve `FlowMetricsSummary` del
  tenant autenticado:
  ```json
  { "publishedFlowCount": 12, "executions24h": 340, "errorRatePct": 2.4, "p95DurationMs": 810 }
  ```
  Ruta bajo `/flows/metrics/...` en vez de `/flows/{flowId}/...` porque es
  agregado a nivel tenant, no por flow — se registra en el controller
  **antes** que `GET /{flowId}` para que Spring no intente resolver
  `metrics` como un `flowId` (mismo cuidado que ya existe implícitamente en
  el orden de rutas de Spring MVC).

Fuera de alcance explícito para este slice:

- **Listado/detalle de ejecuciones individuales** (`GET
  /flows/{flowId}/executions`, paginado, filtros): es lo que alimentará
  `flow-executions.component`/`flow-execution-detail.component` cuando se
  construyan; este slice solo entrega el summary agregado para los 4 cards.
  Se deja el modelo de datos ya preparado (índices, campos) para que ese
  endpoint se agregue después sin migración adicional.
- **Motor de ejecución real**: nada en este slice ejecuta flujos; el
  endpoint `POST .../executions` es un receptor pasivo.
- **Validación de que `flowVersionNumber` corresponda a una `FlowVersion`
  publicada real**: se persiste tal cual la reporta el cliente.

## Persistencia

Nueva migración Flyway, continuando la numeración (`V12` es la última
existente):

- `V13__create_flow_execution.sql`:
  ```sql
  CREATE TABLE flow_execution (
      id BINARY(16) NOT NULL,
      tenant_id BINARY(16) NOT NULL,
      flow_id BINARY(16) NOT NULL,
      flow_version_number INT NOT NULL,
      status VARCHAR(20) NOT NULL,
      started_at TIMESTAMP(6) NOT NULL,
      finished_at TIMESTAMP(6) NOT NULL,
      duration_ms BIGINT NOT NULL,
      error_message TEXT NULL,
      PRIMARY KEY (id),
      KEY idx_flow_execution_tenant_started (tenant_id, started_at, duration_ms),
      CONSTRAINT fk_flow_execution_flow FOREIGN KEY (flow_id) REFERENCES flow (id)
  );
  ```
  El índice compuesto cubre las tres queries de métricas (count total,
  count por status, percentil ordenado) sin table scan.

`FlowExecutionPersistenceAdapter` implementa `FlowExecutionRepository`
(puerto) con dos métodos: `save(FlowExecution)` y
`summarize(tenantId, since)` que ejecuta las dos queries del cálculo de
p95 descrito arriba y arma `FlowMetricsSummary`. `publishedFlowCount` lo
resuelve `FlowMetricsService` reutilizando el `FlowRepository` existente
(no duplica lógica de conteo de flows).

## Seguridad y multitenancy

Igual que el resto: tenant desde `TenantContext.requireTenantId()`. El
`POST` de ejecuciones valida que `flowId` pertenezca al tenant autenticado
antes de insertar (reutiliza `FlowRepository.findById` para el chequeo,
mismo patrón que otros comandos de `FlowService`). `errorMessage` puede
contener texto libre del sistema que reporta — se trata con la misma
disciplina que `draftGraph`: no se expone en logs de autenticación, pero
no se sanitiza further en este slice (no se anticipa que contenga
secretos, es un mensaje de error de ejecución).

## BFF y frontend

`GatewayProxyController`/`GatewayProxyService`
(`backoffice/apps/bff/src/gateway-proxy/`) suman:

- `getFlowMetricsSummary(accessToken)` → `GET flows/metrics/summary`.
- `reportFlowExecution(accessToken, flowId, body)` → `POST
  flows/:flowId/executions` (se agrega por completitud del contrato aunque
  el frontend de este slice no lo llame desde ninguna UI — no hay una
  pantalla de "simular ejecución"; queda listo para cuando exista un
  reportante real).

En `backoffice/apps/integration-mfe/src/app/flow/`:

- `flow.model.ts` — se agrega:
  ```ts
  export type FlowExecutionStatus = 'SUCCESS' | 'FAILURE';

  export interface FlowMetricsSummary {
    publishedFlowCount: number;
    executions24h: number;
    errorRatePct: number;
    p95DurationMs: number | null;
  }
  ```
- `flow.service.ts` — se agrega `getMetricsSummary(): Observable<FlowMetricsSummary>`
  apuntando a `${BASE_URL}/metrics/summary`.
- `flow-list.component.ts` — nuevo signal `metrics = signal<FlowMetricsSummary | null>(null)`
  y `metricsUnavailable = signal(false)`. Se carga en paralelo con
  `flows()` en `ngOnInit` (llamada independiente; si falla, no bloquea la
  tabla — mismo criterio que el resto de la página, donde el estado de la
  tabla es la fuente de verdad de disponibilidad de "Flows").
- `flow-list.component.html` — se agrega un `<div class="kpi-grid">` entre
  `page-header` y el formulario/tabla, con el mismo markup/clases CSS que
  ya usa `dashboard-page.component.html` (`card kpi`, `kpi-label`,
  `kpi-value`, `kpi-note`). Las ~15 líneas de CSS del `.kpi-grid`/`.kpi` se
  duplican en `flow-list.component.css` (ya copiadas desde
  `dashboard-page.component.css`) en vez de extraerlas a un componente o
  estilo compartido — no hay tercer consumidor todavía que justifique esa
  abstracción:
  - FLUJOS PUBLICADOS → `metrics()?.publishedFlowCount`
  - EJECUCIONES 24H → `metrics()?.executions24h`
  - TASA DE ERROR → `metrics()?.errorRatePct` formateado `%`
  - P95 POR EJECUCIÓN → `metrics()?.p95DurationMs` formateado `ms`, o "—"
    si es `null` (sin ejecuciones aún)
  - Si `metricsUnavailable()`, cada card muestra "—" con nota "No
    disponible", mismo patrón que "EN DLQ" en el dashboard.

## Pruebas

Antes de implementar, se agregan pruebas para:

- **Dominio** (`FlowExecutionTest`): `report(...)` calcula `durationMs`
  correctamente, rechaza `finishedAt < startedAt`.
- **Aplicación** (`FlowMetricsServiceTest`): `errorRatePct = 0.0` cuando no
  hay ejecuciones (no `NaN`/excepción); `p95DurationMs = null` cuando no
  hay ejecuciones; cálculo correcto de `errorRatePct` con datos mixtos
  SUCCESS/FAILURE; aislamiento por tenant (ejecuciones de otro tenant no
  contaminan el summary).
- **Persistencia** (`FlowExecutionPersistenceAdapterTest`, Testcontainers
  MySQL): el cálculo de p95 vía `LIMIT/OFFSET` da el mismo resultado que
  calcular el percentil en memoria sobre el mismo dataset (se verifica con
  un dataset de tamaño conocido, ej. 20 duraciones, para que el offset
  esperado sea determinístico); filtro correcto de la ventana de 24h
  (ejecución de hace 25h no cuenta).
- **Web** (`FlowControllerTest`): `POST .../executions` con `flowId`
  inexistente → `404`; con `finishedAt < startedAt` → `422`; `GET
  .../metrics/summary` con tenant sin flows ni ejecuciones → todos los
  campos en su valor por defecto (no error).
- **Frontend**: spec de `flow.service.ts` (nuevo método), `flow-list`
  (cards muestran los valores del summary; cards muestran "—" si la
  llamada de métricas falla, sin afectar el estado de la tabla).
