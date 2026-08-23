---
title: "Arquitectura MicroUI: Shell + Native Federation para el Backoffice"
status: accepted
date: 2026-08-23
deciders: ianache
consulted: []
informed: []
tags: [frontend, angular, microfrontend, backoffice]
---

# Arquitectura MicroUI: Shell + Native Federation para el Backoffice

## Contexto y planteamiento del problema

El Backoffice necesita componerse de un Shell (host) que aloje múltiples Micro
Frontends (MicroUIs) independientes, empezando por el MicroUI "integration"
(administración de `IntegrationProfile`, monitor de mensajes, conectores y
credenciales). El equipo desarrolla con Angular en su versión más reciente y el CLI de
Angular ya migró su builder por defecto a esbuild/Vite, dejando atrás Webpack como
mecanismo de build principal.

¿Con qué mecanismo debe el Shell descubrir, cargar y montar cada MicroUI en runtime,
manteniendo builds independientes por equipo/MicroUI?

## Decision drivers

- Debe soportar builds y despliegues independientes por MicroUI (sin re-compilar el Shell).
- Debe alinearse con el builder moderno de Angular CLI (esbuild/Vite), no forzar
  volver a Webpack.
- Debe permitir compartir dependencias singleton (Angular core, Router, RxJS) entre
  Shell y MicroUIs sin duplicar el runtime de Angular en el navegador.
- Debe minimizar la complejidad operativa para un equipo que hoy solo tiene un MicroUI.

## Opciones consideradas

- Native Federation (Angular)
- Webpack Module Federation (`@angular-architects/module-federation`)
- Web Components (Custom Elements) por MicroUI
- single-spa

## Decision outcome

Opción elegida: **Native Federation (Angular)**, porque es el mecanismo de composición
de micro-frontends recomendado para Angular con el builder moderno (esbuild/Vite),
evita atar el proyecto a Webpack, y permite compartir el runtime de Angular como
singleton entre Shell y MicroUIs sin la complejidad adicional de una capa de
orquestación externa como single-spa.

### Consecuencias

- Buenas: builds independientes por MicroUI; sin duplicación del runtime de Angular;
  compatible con el toolchain moderno de Angular CLI; menor superficie de
  configuración que Module Federation clásico.
- Malas: Native Federation es más reciente que Webpack Module Federation, por lo que
  hay menos recursos de la comunidad y algunos edge cases de configuración pueden
  requerir más investigación directa en la documentación oficial.
- Contrato Shell↔MicroUI: el Shell posee el routing raíz y el layout (nav, header,
  sesión); cada MicroUI posee su propio árbol de rutas internas, cargado de forma
  lazy por el Shell. La comunicación entre Shell y MicroUI usa el Router de Angular y
  tipos compartidos en la librería `shell-contracts` (ver
  `docs/superpowers/specs/2026-08-23-backoffice-microui-architecture-design.md`); no
  hay acoplamiento directo de estado entre MicroUIs.

## Validación

Se valida cuando el Shell carga el MicroUI "integration" en runtime sin necesidad de
recompilar el Shell al desplegar una nueva versión del MicroUI.
