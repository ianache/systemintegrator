# Backoffice Usable UI, Session, and Integration Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Backoffice foundation into a usable single-origin admin experience with a real Shell layout, OIDC session flow, and a read-only Integration Profiles screen.

**Architecture:** The NestJS BFF serves the compiled Shell, owns the Redis-backed OIDC session, and proxies authenticated `/bff/api/**` calls to the existing Gateway. The Angular Shell owns layout and session presentation, while the federated Integration MicroUI owns the read-only profile list and calls the BFF through the browser's same origin.

**Tech Stack:** Angular 22, Native Federation 22.0.6, Nx 23.1.1, NestJS 11, `openid-client` 6, `express-session`, `connect-redis`, Redis 7.4, Playwright, Jest/Vitest, existing Spring Gateway and integration application.

**Spec:** `docs/superpowers/specs/2026-08-23-backoffice-usable-ui-session-integration-design.md`

## Global Constraints

- The BFF is the only public Backoffice origin; local development uses `http://localhost:4000`.
- Browser code must never receive OIDC client secrets or raw access/refresh tokens.
- Effective tenant identity comes from the validated JWT, never from a browser-supplied `X-Tenant-ID`.
- OIDC uses Authorization Code + PKCE with server-side `state` and `codeVerifier`.
- Redis session keys use the existing `backoffice-session:` prefix and an eight-hour HTTP-only session cookie.
- The Shell route `/integration` continues to load the remote named `integration-mfe` from Native Federation.
- `shell-contracts` remains a workspace library and must stay excluded from federation sharing.
- Profile mutations, tenant switching, roles, and production deployment remain out of scope.
- Existing changes in `backoffice/nx.json` must not be reverted or included in unrelated commits.

### Task 1: Establish BFF configuration and session test seams

**Files:**
- Modify: `backoffice/apps/bff/src/app/app.module.ts`
- Modify: `backoffice/apps/bff/src/main.ts`
- Modify: `backoffice/apps/bff/src/session/configure-session.ts`
- Create: `backoffice/apps/bff/src/config/backoffice-config.ts`
- Test: `backoffice/apps/bff/src/config/backoffice-config.spec.ts`

**Interfaces:**
- Produces `BackofficeConfig` with `KEYCLOAK_APPS_ISSUER_URI`, `BFF_OIDC_CLIENT_ID`, `BFF_OIDC_CLIENT_SECRET`, `BFF_SESSION_SECRET`, `BFF_PUBLIC_URL`, `GATEWAY_URI`, and `REDIS_URL`.
- Keeps `configureSession(app, config)` as the session installation entry point.

- [ ] **Step 1: Write configuration tests**

```ts
it('reads all required Backoffice configuration values', () => {
  const config = readBackofficeConfig({
    KEYCLOAK_APPS_ISSUER_URI: 'https://issuer.example/realms/Apps',
    BFF_OIDC_CLIENT_ID: 'backoffice',
    BFF_OIDC_CLIENT_SECRET: 'secret',
    BFF_SESSION_SECRET: 'session-secret',
    BFF_PUBLIC_URL: 'http://localhost:4000',
    GATEWAY_URI: 'http://localhost:8081',
    REDIS_URL: 'redis://localhost:6379',
  });

  expect(config.BFF_PUBLIC_URL).toBe('http://localhost:4000');
  expect(config.GATEWAY_URI).toBe('http://localhost:8081');
});
```

- [ ] **Step 2: Run the focused test and verify it fails because the config seam does not exist**

Run: `npx nx test bff --testPathPattern=backoffice-config.spec.ts`

Expected: FAIL because `readBackofficeConfig` is not defined.

- [ ] **Step 3: Implement the configuration seam and wire `ConfigModule`**

Use `ConfigModule.forRoot({ isGlobal: true })` and expose a typed factory that calls `ConfigService.getOrThrow` for every required value. Keep the existing Redis URL and session cookie behavior, but obtain the values through this typed configuration boundary.

- [ ] **Step 4: Run BFF tests and verify the configuration/session baseline**

Run: `npx nx test bff`

Expected: PASS with all existing BFF tests plus the new configuration tests.

- [ ] **Step 5: Commit the isolated configuration work**

```powershell
git add backoffice/apps/bff/src/app/app.module.ts backoffice/apps/bff/src/main.ts backoffice/apps/bff/src/session/configure-session.ts backoffice/apps/bff/src/config/backoffice-config.ts backoffice/apps/bff/src/config/backoffice-config.spec.ts
git commit -m "feat(backoffice): formalize BFF runtime configuration"
```

### Task 2: Complete the OIDC session lifecycle

**Files:**
- Modify: `backoffice/apps/bff/src/auth/auth.controller.ts`
- Modify: `backoffice/apps/bff/src/auth/auth.service.ts`
- Modify: `backoffice/apps/bff/src/auth/auth.module.ts`
- Create: `backoffice/apps/bff/src/auth/session-types.ts`
- Create: `backoffice/apps/bff/src/auth/auth-callback.spec.ts`
- Modify: `backoffice/apps/bff/src/auth/auth.controller.spec.ts`

**Interfaces:**
- `GET /api/auth/login` redirects to the configured issuer and stores `{ codeVerifier, state }` in the session.
- `GET /api/auth/callback?code=...&state=...` exchanges the code and stores tokens server-side.
- `GET /api/auth/session` returns `{ authenticated: false }` or `{ authenticated: true, tenantId, expiresAt }`.
- `GET /api/auth/logout` destroys the session and redirects to `/`.

- [ ] **Step 1: Add failing tests for callback state validation and session projection**

```ts
it('rejects a callback with a state different from the session state', async () => {
  await request(app.getHttpServer())
    .get('/api/auth/callback?code=code-1&state=wrong-state')
    .expect(400);
});

it('does not expose tokens from the session endpoint', async () => {
  await seedAuthenticatedSession({ access_token: 'private-token', id_token: jwtWithTenant('tenant-a') });
  const response = await request(app.getHttpServer()).get('/api/auth/session').expect(200);
  expect(response.body).toEqual({ authenticated: true, tenantId: 'tenant-a', expiresAt: expect.any(Number) });
  expect(response.body.access_token).toBeUndefined();
});
```

- [ ] **Step 2: Run the focused tests to verify they fail**

Run: `npx nx test bff --testPathPattern=auth-callback.spec.ts|auth.controller.spec.ts`

Expected: FAIL because callback, session, and logout routes are incomplete.

- [ ] **Step 3: Implement callback, session, and logout**

Use `openid-client.authorizationCodeGrant` with the discovery configuration, the stored PKCE verifier, and the returned state. Reject missing or mismatched state before the token exchange. Decode only the ID-token payload needed to expose `tenant_id`; retain token material only in the server session. Destroy the session on logout.

- [ ] **Step 4: Run all BFF tests**

Run: `npx nx test bff`

Expected: PASS with login, callback, session, logout, and existing BFF tests.

- [ ] **Step 5: Commit the OIDC lifecycle**

```powershell
git add backoffice/apps/bff/src/auth
git commit -m "feat(backoffice): complete OIDC session lifecycle"
```

### Task 3: Add authenticated Gateway proxying

**Files:**
- Create: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.ts`
- Create: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.module.ts`
- Create: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.service.ts`
- Create: `backoffice/apps/bff/src/gateway-proxy/gateway-proxy.controller.spec.ts`
- Modify: `backoffice/apps/bff/src/app/app.module.ts`
- Modify: `backoffice/package.json`
- Modify: `backoffice/package-lock.json`

**Interfaces:**
- `GET /api/bff/api/v1/integration-profiles` maps to downstream `GET ${GATEWAY_URI}/api/v1/integration-profiles`.
- The proxy returns `401` for an anonymous session and forwards only the server-side access token for an authenticated session.

- [ ] **Step 1: Add failing proxy tests**

```ts
it('rejects an anonymous profile request', async () => {
  await request(app.getHttpServer()).get('/api/bff/api/v1/integration-profiles').expect(401);
});

it('forwards the server-side bearer token to the Gateway', async () => {
  gatewayMock.get.mockResolvedValue({ status: 200, data: [{ id: 'p-1' }] });
  await seedAuthenticatedSession({ access_token: 'server-token', id_token: jwtWithTenant('tenant-a') });
  await request(app.getHttpServer()).get('/api/bff/api/v1/integration-profiles').expect(200);
  expect(gatewayMock.get).toHaveBeenCalledWith('/api/v1/integration-profiles', expect.objectContaining({ headers: { Authorization: 'Bearer server-token' } }));
});
```

- [ ] **Step 2: Run the focused proxy test and verify it fails**

Run: `npx nx test bff --testPathPattern=gateway-proxy.controller.spec.ts`

Expected: FAIL because the proxy module and guarded route do not exist.

- [ ] **Step 3: Implement the guarded proxy**

Use a controller-scoped session guard. Forward only the allowlisted read-only profile route, map downstream `401/403` to the browser contract, and map transport failures to a stable `502` response. Do not copy `X-Tenant-ID` from the browser request.

- [ ] **Step 4: Run the BFF suite and a local downstream smoke test**

Run: `npx nx test bff` and `Invoke-WebRequest http://localhost:8081/actuator/health`.

Expected: BFF tests pass and the existing Gateway remains reachable.

- [ ] **Step 5: Commit the proxy**

```powershell
git add backoffice/apps/bff/src/gateway-proxy backoffice/apps/bff/src/app/app.module.ts backoffice/package.json backoffice/package-lock.json
git commit -m "feat(backoffice): proxy authenticated profile reads through Gateway"
```

### Task 4: Build the Shell layout and session presentation

**Files:**
- Modify: `backoffice/apps/shell/src/app/app.ts`
- Modify: `backoffice/apps/shell/src/app/app.html`
- Modify: `backoffice/apps/shell/src/app/app.css`
- Create: `backoffice/apps/shell/src/app/session/session.service.ts`
- Create: `backoffice/apps/shell/src/app/session/session.service.spec.ts`
- Create: `backoffice/apps/shell/src/app/layout/header.component.ts`
- Create: `backoffice/apps/shell/src/app/layout/header.component.html`
- Create: `backoffice/apps/shell/src/app/layout/header.component.css`
- Create: `backoffice/apps/shell/src/app/layout/sidebar.component.ts`
- Create: `backoffice/apps/shell/src/app/layout/sidebar.component.html`
- Create: `backoffice/apps/shell/src/app/layout/sidebar.component.css`

**Interfaces:**
- `SessionService.session()` exposes `{ authenticated: boolean; tenantId?: string; expiresAt?: number }`.
- `SessionService.refresh()` calls `/auth/session` with same-origin credentials.
- `SessionService.login()` navigates to `/auth/login`; `logout()` navigates to `/auth/logout`.

- [ ] **Step 1: Add failing SessionService tests**

```ts
it('maps an authenticated session response', () => {
  service.refresh();
  http.expectOne('/auth/session').flush({ authenticated: true, tenantId: 'tenant-a', expiresAt: 1893456000 });
  expect(service.session()).toEqual({ authenticated: true, tenantId: 'tenant-a', expiresAt: 1893456000 });
});

it('maps an anonymous session response', () => {
  service.refresh();
  http.expectOne('/auth/session').flush({ authenticated: false });
  expect(service.session()).toEqual({ authenticated: false });
});
```

- [ ] **Step 2: Run the focused Shell test and verify it fails**

Run: `npx nx test shell --testPathPattern=session.service.spec.ts`

Expected: FAIL because the session service and real layout do not exist.

- [ ] **Step 3: Implement the session service and layout components**

Use Angular signals and `HttpClient` with same-origin requests. Replace `<app-nx-welcome>` in the root template with a semantic layout containing a header, sidebar navigation, router outlet, login action, logout action, tenant label, and accessible status text.

- [ ] **Step 4: Add the default and fallback routes**

Keep `/integration` as the federated route, add a welcome component for `/`, and add a not-found route that redirects to `/`. The root view must not render `NxWelcome`.

- [ ] **Step 5: Run Shell tests and build**

Run: `npx nx test shell` and `npx nx build shell`.

Expected: PASS; the build may retain only the known generated-component CSS budget warning until the generated welcome component is removed.

- [ ] **Step 6: Commit the Shell UI**

```powershell
git add backoffice/apps/shell/src/app
git commit -m "feat(backoffice): add usable Shell layout and session status"
```

### Task 5: Implement the read-only Integration Profiles MicroUI

**Files:**
- Modify: `backoffice/apps/integration-mfe/src/app/remote-entry/entry.routes.ts`
- Modify: `backoffice/apps/integration-mfe/src/app/remote-entry/integration-root.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.model.ts`
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.service.ts`
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile.service.spec.ts`
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.ts`
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.html`
- Create: `backoffice/apps/integration-mfe/src/app/integration-profile/integration-profile-list.component.css`

**Interfaces:**
- `IntegrationProfileService.list()` calls `/bff/api/v1/integration-profiles` and returns `Observable<IntegrationProfile[]>`.
- `IntegrationProfile` includes `id`, `businessDomain`, `externalSource`, `syncDirection`, `active`, and `version`.

- [ ] **Step 1: Add service and component tests for all UI states**

```ts
it('loads profiles through the BFF same-origin endpoint', () => {
  service.list().subscribe((profiles) => expect(profiles[0].id).toBe('p-1'));
  http.expectOne('/bff/api/v1/integration-profiles').flush([{ id: 'p-1', businessDomain: 'orders', externalSource: 'erp', syncDirection: 'INBOUND', active: true, version: 0 }]);
});
```

Add component tests for populated, empty, `401`, `403`, and `502` responses.

- [ ] **Step 2: Run the focused MicroUI tests and verify they fail**

Run: `npx nx test integration-mfe --testPathPattern=integration-profile`

Expected: FAIL because the profile service/list component do not exist.

- [ ] **Step 3: Implement the profile service and list view**

Render an accessible table with the required fields. Use explicit loading, empty, error, and session-expired states. Keep the view read-only and route a `401/403` response to the Shell login flow without exposing backend error details.

- [ ] **Step 4: Run MicroUI tests and build**

Run: `npx nx test integration-mfe` and `npx nx build integration-mfe`.

Expected: PASS.

- [ ] **Step 5: Commit the MicroUI**

```powershell
git add backoffice/apps/integration-mfe/src/app
git commit -m "feat(backoffice): add read-only integration profile view"
```

### Task 6: Serve the built Backoffice from the BFF single origin

**Files:**
- Modify: `backoffice/apps/bff/src/app/app.module.ts`
- Modify: `backoffice/apps/bff/src/main.ts`
- Modify: `backoffice/apps/bff/project.json`
- Modify: `backoffice/package.json`
- Modify: `backoffice/package-lock.json`
- Create: `backoffice/apps/bff/src/app/static-shell-fallback.spec.ts`

**Interfaces:**
- `GET /` serves the Shell `index.html`.
- `GET /integration` serves the Shell entry point so Angular routing can resolve the federated route.
- `/auth/**` and `/bff/api/**` remain API routes and are not shadowed by static fallback.

- [ ] **Step 1: Add a failing static-serving test**

```ts
it('serves the Shell entry point from the BFF origin', async () => {
  await request(app.getHttpServer()).get('/').expect(200).expect('Content-Type', /html/);
});
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `npx nx test bff --testPathPattern=static-shell-fallback.spec.ts`

Expected: FAIL because the BFF currently exposes only `/api` and `/auth/login`.

- [ ] **Step 3: Add static assets and build dependencies**

Install `@nestjs/serve-static`, copy `dist/apps/shell/browser` into the BFF build output as `shell-static`, and configure a fallback that serves `index.html` for non-API application routes. Keep `/api`, `/auth`, and `/bff` ahead of the fallback.

- [ ] **Step 4: Run the BFF build and smoke test**

Run: `npx nx build shell`, `npx nx build integration-mfe`, `npx nx build bff`, then start the built BFF with:

```powershell
$env:BFF_PUBLIC_URL='http://localhost:4000'
$env:BFF_SESSION_SECRET='local-dev-secret-change-me'
$env:REDIS_URL='redis://localhost:6379'
$env:GATEWAY_URI='http://localhost:8081'
node dist/apps/bff/main.js
```

Verify: `Invoke-WebRequest http://localhost:4000/` returns HTML and `Invoke-WebRequest http://localhost:4000/api` returns the BFF response.

- [ ] **Step 5: Commit the single-origin BFF**

```powershell
git add backoffice/apps/bff backoffice/package.json backoffice/package-lock.json
git commit -m "feat(backoffice): serve Shell from the BFF origin"
```

### Task 7: Add integration E2E and acceptance documentation

**Files:**
- Modify: `backoffice/apps/shell-e2e/src/shell.spec.ts`
- Create: `backoffice/apps/shell-e2e/src/session-layout.spec.ts`
- Create: `docs/backoffice-usable-ui-acceptance.md`

- [ ] **Step 1: Extend Playwright coverage**

Add tests that assert `/` renders the Shell layout without `app-nx-welcome`, `/integration` renders the remote marker, and the anonymous state shows the login action. Keep the existing Chromium, Firefox, and WebKit matrix.

- [ ] **Step 2: Run the E2E suite before documenting acceptance**

Run: `npx nx e2e shell-e2e`

Expected: all Shell and MicroUI browser tests pass in the configured browsers.

- [ ] **Step 3: Write the manual acceptance checklist**

Document environment variables, startup commands, login/logout flow, tenant isolation check, profile list states, expected HTTP responses, and cleanup commands. Do not include real Keycloak credentials or tokens.

- [ ] **Step 4: Commit the acceptance coverage**

```powershell
git add backoffice/apps/shell-e2e docs/backoffice-usable-ui-acceptance.md
git commit -m "test(backoffice): cover usable UI acceptance flow"
```

### Task 8: Full verification and handoff

**Files:**
- No source changes expected.

- [ ] **Step 1: Run all Backoffice builds and tests**

Run from `backoffice/`:

```powershell
npx nx run shell:build
npx nx run integration-mfe:build
npx nx run bff:build
npx nx test shell
npx nx test integration-mfe
npx nx test bff
npx nx test shell-contracts
npx nx e2e shell-e2e
```

- [ ] **Step 2: Run the existing backend regression suite**

Run from the repository root: `mvn test`.

- [ ] **Step 3: Validate the final worktree**

Run: `git diff --check` and `git status --short`. Confirm that generated `node_modules`, `dist`, and cache directories are ignored and that the pre-existing `backoffice/nx.json` change remains separate from the iteration commits.

- [ ] **Step 4: Report the handoff**

Record the verified URLs, test counts, required environment variables, known warnings, and any intentionally out-of-scope behavior in the final acceptance document.
