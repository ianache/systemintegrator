---
title: "Modelo de tenant del Backoffice v1: tenant fijo por sesion"
status: accepted
date: 2026-08-23
deciders: ianache
consulted: []
informed: []
tags: [multitenancy, backoffice, security]
---

# Modelo de tenant del Backoffice v1: tenant fijo por sesión

## Contexto y planteamiento del problema

El diseño UX de "Integration Console" incluye un selector de tenant en el header,
sugiriendo que un admin podría operar sobre distintos tenants sin volver a
autenticarse. Sin embargo, un JWT trae normalmente un único claim `tenant_id`, y el
Gateway existente confía ciegamente en ese valor por diseño (ver ADR-0003). Permitir
cambio de tenant en caliente implicaría que el BFF envíe al Gateway un tenant
distinto al que el JWT original certifica, lo cual requeriría un nuevo límite de
confianza (caller interno privilegiado) que el Gateway no tiene hoy.

## Decision drivers

- No introducir un nuevo límite de confianza en el Gateway en esta primera fase.
- Mantener el invariante "el tenant siempre viene de un JWT validado" sin excepciones.
- Priorizar time-to-value: la primera fase del Backoffice no requiere administración
  multi-tenant simultánea.

## Opciones consideradas

- Sin cambio de tenant en caliente: el JWT del admin trae un `tenant_id` fijo.
- Admin multi-tenant real: el JWT trae varios tenants autorizados y el Gateway gana un
  nuevo mecanismo de confianza para el tenant seleccionado por el BFF.

## Decision outcome

Opción elegida: **sin cambio de tenant en caliente**. El JWT de cada admin del
Backoffice (realm `Apps`) trae un único `tenant_id` fijo, igual que cualquier otro
cliente del Gateway. El selector de tenant del diseño UX queda deshabilitado o
limitado a mostrar el único tenant disponible en esta fase.

### Consecuencias

- Buenas: cero cambios al modelo de confianza del Gateway; se reutiliza exactamente
  el mismo contrato que ya usan otros clientes de la plataforma.
- Malas: un admin que deba operar sobre múltiples tenants necesita hoy múltiples
  cuentas/sesiones (una por tenant), o un rol de administración por tenant asignado
  explícitamente en Keycloak.
- Fase futura: si se requiere administración multi-tenant real, se deberá diseñar un
  nuevo ADR que defina el mecanismo de confianza entre BFF y Gateway para tenant
  seleccionado dinámicamente (ej. mTLS o client credentials propios del BFF como
  caller interno privilegiado), fuera del alcance de este diseño.

## Validación

Se valida verificando que ningún flujo del Backoffice v1 permite que un admin acceda a
datos de un tenant distinto al `tenant_id` de su propio JWT.
