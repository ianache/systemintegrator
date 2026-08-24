# Backoffice usable UI acceptance checklist

This checklist validates the Backoffice Shell, BFF session boundary, and Integration Profiles MicroUI. Use an authorized non-production environment for identity-provider checks. Do not put Keycloak credentials, client secrets, session secrets, access tokens, refresh tokens, or copied browser cookies in this document, commands, logs, or screenshots.

## Safe configuration and startup

The following non-secret values may be set for a local run. Replace the example hosts and ports to match the local environment.

| Variable | Purpose | Example value |
| --- | --- | --- |
| `PORT` | BFF listener port | `4000` |
| `BFF_PUBLIC_URL` | Browser-visible BFF origin | `http://localhost:4000` |
| `REDIS_URL` | Redis session-store address | `redis://localhost:6379` |
| `GATEWAY_URI` | Middleware Gateway origin | `http://localhost:8081` |

The BFF additionally requires private OIDC and session configuration. Provide those values only through the approved local secret mechanism; do not echo, commit, or paste their values. The structural Playwright suite below does not need them because it starts the Shell and remote directly.

From `backoffice/`, start the credential-free structural browser checks:

```powershell
npx nx e2e shell-e2e
```

For a single-origin manual check, build the Shell and BFF, start Redis and the authorized Gateway/identity-provider dependencies, provide private BFF configuration through the approved secret mechanism, then start the BFF:

```powershell
npx nx run shell:build
npx nx run bff:build
$env:PORT = '4000'
$env:BFF_PUBLIC_URL = 'http://localhost:4000'
$env:REDIS_URL = 'redis://localhost:6379'
$env:GATEWAY_URI = 'http://localhost:8081'
node dist/apps/bff/main.js
```

## Manual acceptance

- [ ] Open `http://localhost:4000/`. It returns Shell HTML from the BFF origin and shows the Backoffice header, primary navigation, welcome content, and an anonymous status; the generated Nx welcome component is absent.
- [ ] Request `GET /auth/session` without a session. It returns `200` with `{"authenticated":false}` and does not include access or refresh tokens.
- [ ] Select **Log in**. The browser navigates to `/auth/login`, which redirects to the authorized Apps Keycloak realm. Complete login only with an approved test account; do not record credentials or tokens.
- [ ] After the callback returns to `/`, verify the header says **Signed in** and displays the authenticated tenant identifier.
- [ ] Open `/integration`. The Integration MicroUI loads and calls `GET /bff/api/v1/integration-profiles`; an authenticated valid session receives `200` with only that tenant's profiles.
- [ ] Check list states using approved tenant data or controlled downstream responses: populated list (`200` with entries), empty list (`200` with `[]`), session-expired (`401`), forbidden (`403`), and unavailable downstream (`502`). No edit, create, delete, or tenant-switch action is present.
- [ ] Tenant isolation: while signed in as tenant A, send a browser request with an altered `X-Tenant-ID` header and confirm it cannot change the profiles returned. Repeat with authorized tenant B and confirm only tenant B data appears. Do not use copied bearer tokens to perform this check.
- [ ] Select **Log out**. The browser navigates through `/auth/logout` and returns to the anonymous Shell. A subsequent `GET /bff/api/v1/integration-profiles` returns `401`; the old browser session no longer grants access.
- [ ] Verify API-route precedence: `/auth/**`, `/api/**`, and `/bff/api/**` are not replaced by Shell HTML. Unknown non-API application routes may use the Shell SPA fallback.

## Cleanup

- [ ] Close the browser and stop the BFF, Shell, remote, Redis, and Gateway processes started for the check.
- [ ] Remove transient local environment variables or terminate the shell session that held them.
- [ ] Clear only the disposable local browser profile/session used for acceptance testing; never delete shared Redis data or another developer's environment.
- [ ] Confirm no secret, token, cookie, or browser trace was added to Git before committing.
