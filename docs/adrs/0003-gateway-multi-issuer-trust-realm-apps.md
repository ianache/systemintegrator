---
title: "Confianza multi-issuer en el Gateway: extensión al realm Apps"
status: accepted
date: 2026-08-23
deciders: ianache
consulted: []
informed: []
tags: [security, gateway, keycloak, backoffice]
---

# Confianza multi-issuer en el Gateway: extensión al realm Apps

## Contexto y planteamiento del problema

El Gateway (`gateway/`) valida JWT contra un único issuer hoy configurado
(`KEYCLOAK_ISSUER_URI`, por defecto
`https://oauth2.qa.comsatel.com.pe/realms/microservicios`), y `TenantClaimGatewayFilter`
deriva el tenant exclusivamente de ese JWT validado. El Backoffice autentica a sus
administradores contra un realm distinto, `Apps`, en el mismo servidor Keycloak. Un
token emitido por `realms/Apps` tiene un issuer diferente y sería rechazado por la
configuración actual del Gateway. Hay que decidir cómo el Gateway acepta tráfico
autenticado por ambos realms sin debilitar su modelo de confianza.

## Decision drivers

- El invariante de seguridad actual del Gateway (tenant siempre derivado de un JWT
  validado, nunca de un header de cliente) no debe debilitarse.
- El Backoffice necesita ser un ciudadano de primera clase del Gateway existente, sin
  duplicar infraestructura de proxy/enrutamiento.
- La solución debe ser operativamente simple de mantener sobre Spring Security.

## Opciones consideradas

- Gateway acepta múltiples issuers (resolver de issuer múltiple de Spring Security)
- Keycloak Token Exchange entre realms (RFC 8693 / identity brokering)
- El Backoffice reutiliza directamente el realm `microservicios` (se abandona el
  requisito de un realm `Apps` separado)

## Decision outcome

Opción elegida: **Gateway acepta múltiples issuers**, extendiendo
`GatewaySecurityConfig` con un resolver de issuer múltiple estándar de Spring Security
(`JwtIssuerReactiveAuthenticationManagerResolver`), confiando en
`realms/microservicios` (tráfico actual) y en `realms/Apps` (admins del Backoffice). El
realm `Apps` debe configurar un protocol mapper que emita el claim `tenant_id` con el
mismo contrato que usa hoy `microservicios`.

Se descarta Token Exchange entre realms por su fragilidad operativa (depende de
identity brokering configurado entre realms y de soporte variable según
versión/licencia de Keycloak/RH-SSO), y se descarta reutilizar `microservicios`
directamente porque `Apps` es el estándar organizacional ya fijado para portales
internos.

### Consecuencias

- Buenas: el invariante "tenant deriva solo de un JWT validado" se mantiene intacto,
  solo se amplía la lista de issuers confiables; cambio acotado y localizado en
  `GatewaySecurityConfig`; no introduce un nuevo límite de confianza ni tráfico
  interno privilegiado.
- Malas: el Gateway pasa a depender de que el realm `Apps` mantenga el contrato de
  claim `tenant_id` sincronizado con `microservicios`; cualquier deriva en ese
  contrato rompe silenciosamente la resolución de tenant para admins del Backoffice.
- Los tests deterministas existentes del Gateway (`GatewaySecurityTest`,
  `TenantClaimGatewayFilterTest`) deben extenderse para cubrir ambos issuers con JWKS
  locales, sin contactar Keycloak QA.

## Validación

Se valida con un test determinista que envía un JWT válido firmado con un JWKS local
simulando el issuer `realms/Apps` (con claim `tenant_id`) y verifica que el Gateway lo
acepta y propaga el tenant correctamente, igual que hace hoy con `realms/microservicios`.
