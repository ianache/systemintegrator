---
okf_version: "0.2"
title: Revisión de la documentación C4
status: REQUIRES_REVIEW
human-reviewed: false
---

# Revisión

**Estado: `REQUIRES_REVIEW`.** No se declara `READY_FOR_DEV`. No hay revisión humana (`human-reviewed: false`).

## Cumple
- Un foco por vista, con borde rojo en los diagramas de flujo y de clases. En las secuencias el foco va marcado con `★`.
- Context, Container, Component y Code usan los mismos nombres y la misma arquitectura (hexagonal).
- Los enlaces apuntan a archivos reales del repositorio.

## Pendientes
1. **No hay DCP, DTC ni OpenAPI.** La regla de una secuencia por endpoint de un API REST no se pudo aplicar. Busqué `openapi*.y*ml` y `api.yaml` y no encontré ninguno. `integration-app` expone unos 40 endpoints en 8 controllers y solo se documentan 2 journeys con ellos (S2, parcialmente S3). La cobertura por endpoint es 0 %.
2. **Component y Code cubren un subconjunto.** El Component omite `flow`, `lookup`, `vehicle`, `monitor` y `batch`. El Code documenta una sola clase.
3. **Dos relaciones `INFERRED`** (ver [traceability-report.md](traceability-report.md)).
4. **Los `.mmd` no se renderizaron** a SVG/PNG ni se validaron con el CLI de Mermaid. La sintaxis no está probada.
5. **Sin archivo `diagrams/sequence-e2e.mmd` único:** hay tres `.mmd` de secuencia, uno por journey.
6. **El frontmatter `okf_version: "0.2"` es mínimo.** No tengo el esquema OKF v0.2, así que puede no cumplirlo.
