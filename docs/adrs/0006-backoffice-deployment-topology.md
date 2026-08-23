---
title: "Topologia de despliegue del Backoffice: BFF como ingreso publico independiente"
status: accepted
date: 2026-08-23
deciders: ianache
consulted: []
informed: []
tags: [deployment, compose, backoffice, gateway]
---

# Topología de despliegue del Backoffice: BFF como ingreso público independiente

## Contexto y planteamiento del problema

El stack actual (`compose.yaml`) publica un único puerto host para tráfico de negocio:
el Gateway en `8081`, que enruta `/api/**` hacia `app:8080`. Hay que decidir cómo se
conecta el nuevo Backoffice (Shell + BFF) a esta infraestructura: si comparte el mismo
punto de entrada público que el Gateway, o si expone su propio ingreso.

## Decision drivers

- No mezclar la responsabilidad del Gateway (proxy de API de negocio multi-tenant) con
  la de servir un portal administrativo con su propia sesión.
- Mantener el Gateway como un componente estable y de bajo cambio.
- Simplicidad operativa para desplegar y escalar el Backoffice de forma independiente.

## Opciones consideradas

- BFF como ingreso público independiente (puerto propio), llamando al Gateway como
  downstream.
- BFF detrás del Gateway, con una nueva ruta `/backoffice/**` en el Gateway.

## Decision outcome

Opción elegida: **BFF como ingreso público independiente**. El navegador entra
directo al BFF (NestJS) en su propio puerto publicado (ej. `4000`); el BFF sirve (o
delega a un Nginx dedicado) los assets estáticos del Shell/MicroUIs y expone
`/bff/api/**`. El BFF, ya con el token de sesión resuelto, llama al Gateway existente
(`http://middleware:8081` dentro de la red de Compose) como un downstream más — el
Gateway no gana responsabilidad de reverse-proxy de portal.

Nuevos servicios en `compose.yaml`, junto a
`mysql/redis/kafka/app/middleware/prometheus/grafana`:

- `backoffice-bff`: imagen Node/NestJS, puerto propio, env
  `KEYCLOAK_APPS_ISSUER_URI`, `GATEWAY_URI=http://middleware:8081`, healthcheck
  `/health`.
- `backoffice-shell`: build estático servido por Nginx.

### Consecuencias

- Buenas: el Gateway conserva una responsabilidad única y estable (proxy de API de
  negocio multi-tenant); el Backoffice puede escalar, desplegarse y versionarse de
  forma independiente del Gateway.
- Malas: dos puntos de entrada públicos distintos (`8081` para API de negocio, un
  puerto nuevo para el Backoffice) en vez de uno solo; requiere gestionar CORS/orígenes
  si en el futuro el Shell llama directamente a servicios que no sean el propio BFF
  (no es el caso en este diseño: el Shell solo llama al BFF).

## Validación

Se valida con `docker compose config --quiet` sobre el `compose.yaml` extendido
(sin secretos versionados) y con `docker compose up` levantando `backoffice-bff` y
`backoffice-shell` saludables junto al resto del stack existente.
