# Backoffice Portal Docker Compose Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and run the Backoffice Shell, Integration MicroUI, and BFF as services in the repository Docker Compose stack.

**Architecture:** Build each Backoffice artifact from the existing Nx workspace. Serve Shell and MicroUI with Nginx; proxy Shell `/bff/**` and `/auth/**` to the BFF. Run the BFF on port 4000 with Gateway and Redis service URLs from the Compose network.

**Tech Stack:** Docker Compose, multi-stage Node 22 builds, Nginx, Nx 23, Angular 22, NestJS 11.

**Spec:** `docs/superpowers/specs/2026-08-26-backoffice-compose-design.md`

## Global Constraints

- Keep the existing backend services and ports unchanged.
- Use internal service names for container-to-container calls.
- Keep OIDC values configurable through environment variables; do not commit real credentials.
- Shell and MicroUI must be built from the committed `backoffice` workspace.

---

### Task 1: Container assets for Shell and MicroUI

**Files:**
- Create: `backoffice/docker/shell.Dockerfile`
- Create: `backoffice/docker/microui.Dockerfile`
- Create: `backoffice/docker/shell.nginx.conf`
- Create: `backoffice/docker/microui.nginx.conf`

**Interfaces:**
- Produces Shell static assets at `/usr/share/nginx/html` on port 80.
- Produces MicroUI federation assets at `/usr/share/nginx/html` on port 80.
- Shell proxies `/bff/` and `/auth/` to `http://backoffice-bff:4000`.

- [ ] Build Shell with `npm ci` and `npx nx build shell`.
- [ ] Build MicroUI with `npm ci` and `npx nx build integration-mfe`.
- [ ] Configure Nginx SPA fallback for Shell and MicroUI static assets.
- [ ] Configure Shell proxy routes and preserve `/remoteEntry.json` on MicroUI.
- [ ] Build both images locally and verify their output directories contain HTML and federation files.
- [ ] Commit with `feat: containerize backoffice frontend artifacts`.

### Task 2: Container asset for BFF

**Files:**
- Create: `backoffice/docker/bff.Dockerfile`
- Modify: `backoffice/apps/bff/src/main.ts` only if a health endpoint is required by the existing app contract.

**Interfaces:**
- Produces a Node runtime image listening on port 4000.
- Consumes `BFF_SESSION_SECRET`, `BFF_PUBLIC_URL`, `BFF_OIDC_CLIENT_ID`, `BFF_OIDC_CLIENT_SECRET`, `KEYCLOAK_APPS_ISSUER_URI`, `GATEWAY_URI`, and `REDIS_URL`.

- [ ] Build BFF with `npm ci` and `npx nx build bff`.
- [ ] Run the generated BFF bundle with `node main.js`.
- [ ] Verify the existing health endpoint or add the smallest health route required by the Compose healthcheck.
- [ ] Build the BFF image and verify it starts with non-secret local defaults.
- [ ] Commit with `feat: containerize backoffice bff`.

### Task 3: Compose integration

**Files:**
- Modify: `compose.yaml`
- Modify: `README.md` or create `docs/backoffice-local-runbook.md`

**Interfaces:**
- Adds `backoffice-microui` on host port 4202.
- Adds `backoffice-shell` on host port 4200.
- Adds `backoffice-bff` on host port 4000.
- Connects BFF to `middleware`, `redis`, and the existing internal network.

- [ ] Add the three services with build contexts, networks, environment, dependencies, and healthchecks.
- [ ] Keep `app`, `middleware`, MySQL, Kafka, Redis, Vault, Prometheus, and Grafana behavior unchanged.
- [ ] Add a local runbook with startup, health, browser URL, and shutdown commands.
- [ ] Run `docker compose config --quiet`.
- [ ] Run `docker compose up -d --build` and verify all portal services are healthy.
- [ ] Commit with `feat: add backoffice portal to compose`.

### Task 4: End-to-end manual verification

**Files:**
- Test: generated Docker/HTTP verification results; no source changes unless a concrete defect is found.

- [ ] Request Shell `/` and verify HTML is returned.
- [ ] Request MicroUI `/remoteEntry.json` and verify JSON is returned.
- [ ] Request BFF `/health` and verify a healthy response.
- [ ] Open `http://localhost:4200` and verify the Shell renders.
- [ ] Open `/integration` and verify the MicroUI route loads.
- [ ] Verify the anonymous session/login action is visible without requiring credentials.
- [ ] Record any limitation caused by missing external Keycloak credentials separately from application failures.
