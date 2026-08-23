---
title: "Patrón BFF con sesión server-side para el Backoffice"
status: accepted
date: 2026-08-23
deciders: ianache
consulted: []
informed: []
tags: [security, bff, oidc, keycloak, backoffice]
---

# Patrón BFF con sesión server-side para el Backoffice

## Contexto y planteamiento del problema

El Backoffice es una SPA (Shell Angular + MicroUIs) que necesita autenticarse contra
Keycloak y llamar a las APIs de la plataforma de integración a través del Gateway
existente. Hay que decidir dónde vive la lógica OIDC y si los tokens de acceso llegan
o no al navegador.

## Decision drivers

- Minimizar la superficie de exposición de tokens a ataques XSS en el navegador.
- Alinearse con la recomendación actual de OWASP/Keycloak para SPAs backed by BFF.
- El BFF debe actuar como guardián real de seguridad, no solo como proxy de red.

## Opciones consideradas

- BFF Session Pattern (Authorization Code + PKCE ejecutado por el BFF como cliente
  confidencial; sesión server-side vía cookie HttpOnly)
- Token Relay simple (login OIDC en el navegador con cliente público; BFF solo reenvía
  el `Authorization` header recibido)

## Decision outcome

Opción elegida: **BFF Session Pattern**, porque evita que los tokens de acceso o
refresco toquen el navegador en cualquier momento, reduciendo drásticamente el
impacto de un XSS en el Shell o en cualquier MicroUI. El BFF (NodeJS) ejecuta el flujo
Authorization Code + PKCE como cliente confidencial contra Keycloak realm `Apps`,
mantiene los tokens en el servidor, y expone al navegador únicamente una cookie de
sesión `HttpOnly` + `Secure` + `SameSite`.

### Consecuencias

- Buenas: superficie de ataque XSS reducida al mínimo (no hay tokens robables desde
  JS de navegador); el BFF controla renovación de tokens, expiración y logout de
  forma centralizada.
- Malas: el BFF se convierte en un componente stateful (sesión), lo que añade
  requisitos de almacenamiento de sesión (en memoria para desarrollo; a definir
  almacenamiento distribuido — ej. Redis, ya presente en el stack — si el BFF escala
  horizontalmente) y disponibilidad.
- El Shell nunca implementa lógica OIDC propia; todo login/logout se delega al BFF vía
  redirects a `/auth/login` y `/auth/logout`.

## Validación

Se valida inspeccionando el tráfico de red del navegador durante el login: ningún
response visible al JS del cliente debe contener un `access_token` o `refresh_token`.
