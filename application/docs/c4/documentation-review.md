---
okf_version: "0.2"
title: Revisión C4 - integration-app
status: REQUIRES_REVIEW
human-reviewed: false
---

# Revisión

**Estado: `REQUIRES_REVIEW`.** No es `READY_FOR_DEV`: no hay revisión humana, los diagramas no se renderizaron y no hay especificación OpenAPI.

## Cobertura
36 de 36 endpoints tienen secuencia ([índice](sequence-e2e.md)). Se derivaron de los controllers, no de una especificación.

## Hallazgos del código
1. **Errores de dominio sin mapear en lookups y vehicles.** `ApiExceptionHandler` tiene `basePackages = "com.cl2.integration.adapter.in.web"`. VIN duplicado, vehículo inexistente y clave de lookup duplicada probablemente responden 500, no 400.
2. **Sync de perfil inactivo → 500.** `triggerSync` lanza `IllegalStateException` sin handler dedicado. Probablemente se esperaba 409 o 422.
3. **Sync de perfil pausado:** no comprueba `paused` y despacha igual.
4. **pause/resume/delete repetidos:** probablemente den 409 `INTEGRATION_PROFILE_CONFLICT` por la versión esperada. Falta una prueba que lo confirme.
5. **N+1:** `GET /integration-profiles` lee `integration_sync_state` por perfil y `GET /flows` consulta métricas por flow.
6. **Retry y replay hacen llamadas HTTP salientes dentro de una transacción** (`@Transactional`).
7. **Retry INBOUND:** cualquier excepción del dispatch se convierte en dead-letter y responde 200.
8. **Listado de mensajes:** pagina a 200 filas por tabla antes de filtrar por `status`, así que el resultado puede ser incompleto.
9. **Publicación de versiones de flow:** el número se calcula contando las existentes. No verifiqué si hay restricción única.
10. **No hay 403 en la app.** Solo viene del gateway. La app responde 400 `TENANT_HEADER_MISSING` o `TENANT_HEADER_MALFORMED`.

## Pendientes
- Los diagramas no se validaron con Mermaid.
- Los nombres de tabla MySQL se infirieron de repositorios y migraciones.
- Los enlaces de las secuencias los generaron agentes y no se comprobaron uno a uno.
- Component y Code cubren un subconjunto: foco en perfiles.
- El frontmatter OKF es mínimo.
