---
title: "Workspace Nx monorepo para el Backoffice"
status: accepted
date: 2026-08-23
deciders: ianache
consulted: []
informed: []
tags: [repo-structure, nx, backoffice]
---

# Workspace Nx monorepo para el Backoffice

## Contexto y planteamiento del problema

El repositorio actual ya es un monorepo Maven (root `pom.xml`, con `gateway/` como
módulo hermano de la aplicación de integración). Hay que decidir dónde viven el Shell,
el MicroUI "integration" y el BFF NodeJS, y cómo se gestionan sus builds
independientes dentro (o fuera) de ese repositorio.

## Decision drivers

- Consistencia con el patrón actual del repositorio (toda la plataforma en un solo
  lugar).
- Minimizar overhead de coordinación para un solo MicroUI inicial (contratos de
  versión, pipelines duplicados).
- Facilitar compartir tipos/contratos TypeScript entre Shell, MicroUI y BFF.

## Opciones consideradas

- Mismo repositorio, nuevo workspace Nx bajo `backoffice/`
- Repositorios separados por Shell, por MicroUI(s) y por BFF

## Decision outcome

Opción elegida: **mismo repositorio, nuevo workspace Nx bajo `backoffice/`**
(hermano de `gateway/`), con Shell, MicroUI "integration" y BFF como proyectos Nx
independientes:

```text
backoffice/
  apps/
    shell/
    integration-mfe/
    bff/
  libs/
    shared-ui/
    shell-contracts/
```

Nx es el estándar de facto para monorepos Angular con múltiples micro-frontends y
tiene soporte oficial tanto para Angular (Native Federation) como para NestJS.

### Consecuencias

- Buenas: un solo lugar para toda la plataforma; contratos compartidos
  (`shell-contracts`) sin necesidad de publicar paquetes npm privados; build afectado
  (`nx affected`) evita recompilar todo el workspace en cada cambio.
- Malas: acopla el ciclo de vida de release del Backoffice al mismo repositorio que el
  backend Java/Maven, requiriendo pipelines de CI separados mecánicamente por
  ecosistema (Maven vs Node/Nx) dentro del mismo repo.
- Si en el futuro se requiere despliegue e independencia total de equipos por
  MicroUI, se puede migrar un proyecto Nx a su propio repositorio sin rediseñar la
  arquitectura de composición (Native Federation no depende de la estructura de
  repositorio).

## Validación

Se valida cuando `nx build shell`, `nx build integration-mfe` y `nx build bff` corren
de forma independiente sin recompilar el backend Java, y `nx affected` detecta
correctamente qué proyectos cambian ante un commit dado.
