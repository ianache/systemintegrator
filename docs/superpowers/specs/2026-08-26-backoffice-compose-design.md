# Backoffice Portal Docker Compose Design

## Goal

Make the existing Backoffice Shell, Integration MicroUI, and NestJS BFF available as a runnable local portal alongside the integration backend.

## Architecture

The browser enters through the Shell container on port 4200. Nginx serves the Shell assets and proxies `/bff/**` and `/auth/**` to the BFF on the internal Compose network. The Shell loads the Integration MicroUI from the independently served MicroUI container on port 4202. The BFF keeps the OIDC session server-side, stores sessions in Redis, and calls the existing middleware at `http://middleware:8081`.

## Runtime contract

- Shell: `http://localhost:4200`
- Integration MicroUI remote entry: `http://localhost:4202/remoteEntry.json`
- BFF: `http://localhost:4000`
- BFF downstream gateway: `http://middleware:8081`
- BFF session store: `redis://integration-redis:6379`
- OIDC issuer: configurable with `KEYCLOAK_APPS_ISSUER_URI`; the Compose default is a local Keycloak URL and login remains configurable.

## Operational requirements

- Docker Compose must build and start `backoffice-bff`, `backoffice-shell`, and `backoffice-microui`.
- BFF and Shell must wait for their required dependencies and expose healthchecks.
- Shell must proxy browser API/auth requests to the BFF so the browser uses one origin.
- MicroUI must serve `remoteEntry.json` and static federation chunks.
- Existing backend services and unrelated working-tree changes must remain untouched.
