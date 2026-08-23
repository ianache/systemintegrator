---
title: "Seleccion de framework NodeJS del BFF: NestJS"
status: accepted
date: 2026-08-23
deciders: ianache
consulted: []
informed: []
tags: [bff, nodejs, nestjs, backoffice]
---

# Selección de framework NodeJS del BFF: NestJS

## Contexto y planteamiento del problema

El BFF del Backoffice (ver ADR-0002) necesita implementar un flujo OIDC completo
(Authorization Code + PKCE), gestión de sesión server-side, y un proxy autenticado
hacia el Gateway. Hay que elegir el framework NodeJS sobre el que se construye, dado
que vivirá como proyecto Nx (ver ADR-0005) junto a Shell y MicroUIs Angular.

## Decision drivers

- Madurez del ecosistema para OIDC/sesión (guards, interceptors, middlewares
  probados).
- Soporte oficial/first-class dentro de un workspace Nx.
- Afinidad de filosofía (DI, decoradores) con el equipo que ya trabaja en Angular,
  reduciendo la curva de aprendizaje al alternar entre Shell y BFF.
- Mantenibilidad a medida que se agreguen más MicroUIs y, por tanto, más guards/rutas
  proxied.

## Opciones consideradas

- NestJS
- Express minimalista
- Fastify

## Decision outcome

Opción elegida: **NestJS**, por su capa de convenciones (DI, guards, interceptors) que
encaja directamente con las necesidades de un BFF orientado a sesión (guard de sesión
reutilizable, interceptor de proxy autenticado), su integración madura con
`@nestjs/passport` y `openid-client` para OIDC, su soporte oficial dentro de Nx, y la
familiaridad de filosofía (decoradores/DI) que ya tiene el equipo por trabajar en
Angular.

### Consecuencias

- Buenas: guards y interceptors reutilizables para sesión y proxy autenticado;
  estructura modular clara a medida que crezcan los MicroUIs y sus rutas proxied;
  integración de primera clase con Nx.
- Malas: mayor peso/convención inicial que Express minimalista; el equipo debe
  aprender las convenciones de NestJS si no las conoce ya (mitigado por la
  familiaridad previa con Angular).
- Se descarta Fastify por tener menor madurez de plugins OIDC/sesión y menor soporte
  first-class en Nx comparado con NestJS/Express.

## Validación

Se valida cuando el proyecto Nx `backoffice/apps/bff` corre `nx serve bff` y
`nx test bff` usando los generadores/ejecutores oficiales de `@nx/nest`.
