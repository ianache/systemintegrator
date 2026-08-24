# Backoffice Usable UI, Session, and Integration Profiles Design

## Goal

Turn the current Backoffice foundation into a minimally usable administrative experience: a real Shell layout, OIDC session flow through the BFF, and a read-only Integration Profiles screen for the authenticated tenant.

## Scope

This iteration includes:

- A Shell layout with header, navigation, content area, responsive behavior, and a clear visual identity for Backoffice.
- A single-origin BFF that serves the built Shell and exposes authentication/session endpoints.
- OIDC Authorization Code + PKCE login, Redis-backed server sessions, and logout.
- Session status in the Shell, including authenticated state and tenant identifier.
- A federated `/integration` MicroUI with a read-only Integration Profiles list.
- A BFF gateway proxy that forwards authenticated requests to the existing middleware Gateway.
- Loading, empty, unauthorized/session-expired, and downstream-error states.
- Unit, integration, E2E, and focused manual acceptance coverage.

This iteration does not include profile creation, editing, deletion, tenant switching, role/permission administration, production deployment, or a visual redesign of the existing integration backend.

## Architecture

The BFF is the only public Backoffice origin. It serves the compiled Shell, owns the browser session, and exposes `/auth/**` and `/bff/api/**`. The browser never receives or stores the OIDC client secret, and browser-originated tenant headers are not trusted.

```text
Browser
  -> Backoffice BFF: Shell assets, /auth/**, /bff/api/**
  -> Keycloak Apps realm: Authorization Code + PKCE
  -> Middleware Gateway: authenticated downstream API calls
  -> Integration application: tenant-scoped profiles
```

The Shell keeps presentation and session state concerns separate from the federated Integration MicroUI. Shared route and session contracts remain local workspace libraries rather than federated runtime packages.

## Authentication and session behavior

1. An unauthenticated user opening the Shell sees a login action and no tenant-scoped data.
2. `/auth/login` creates a fresh PKCE verifier and CSRF state, stores them server-side in Redis, and redirects to the configured Keycloak Apps issuer.
3. `/auth/callback` validates state, exchanges the authorization code with the PKCE verifier, and stores the resulting token set server-side.
4. `/auth/session` returns only a safe session projection: `authenticated`, optional `tenantId`, and optional `expiresAt`; access and refresh tokens never appear in the response.
5. `/auth/logout` destroys the server session and redirects to the Shell.
6. The BFF proxy attaches the server-side access token when calling the Gateway. The browser cannot override the token-derived tenant.
7. Missing or expired sessions return `401`/`403` according to the endpoint contract and the Shell offers a recoverable login path.

Required configuration names:

- `KEYCLOAK_APPS_ISSUER_URI`
- `BFF_OIDC_CLIENT_ID`
- `BFF_OIDC_CLIENT_SECRET`
- `BFF_SESSION_SECRET`
- `BFF_PUBLIC_URL`
- `GATEWAY_URI`
- `REDIS_URL`

## UI behavior

The Shell layout contains:

- Header: product name, authenticated tenant, session status, and logout action.
- Sidebar/navigation: a link to Integration Profiles and an active-route indicator.
- Main content: the active federated MicroUI route.
- Empty/error feedback: accessible text and retry/login actions.

The Integration MicroUI contains:

- Page title and short explanatory text.
- Read-only table/list of profiles with business domain, external source, direction, active state, and version.
- Loading indicator while fetching.
- Empty state when the tenant has no profiles.
- Error state with retry action.
- Session-expired state that routes the user back through BFF login.

The initial route `/` displays the Shell layout and a welcome/dashboard state. `/integration` displays the profile list. Unknown routes resolve to a useful not-found state or redirect to `/`.

## API boundaries

The browser calls the BFF only:

- `GET /auth/session`
- `GET /auth/login`
- `GET /auth/callback`
- `GET /auth/logout`
- `GET /bff/api/v1/integration-profiles`

The BFF calls `${GATEWAY_URI}/api/v1/integration-profiles` with the server-side bearer token. It must not accept a browser-supplied bearer token as a substitute for the session.

## Testing and acceptance

Automated coverage must include:

- Shell layout renders at `/` and does not render the generated Nx welcome component.
- Session service maps anonymous and authenticated `/auth/session` responses.
- Login stores state/verifier and redirects to the configured issuer.
- Callback rejects mismatched state and does not create a session.
- Logout destroys the session.
- Proxy rejects anonymous access and forwards the server-side bearer token for authenticated access.
- Integration MicroUI renders loading, populated, empty, error, and unauthorized states.
- Playwright verifies the Shell-to-MicroUI route and the primary anonymous flow.

Manual acceptance must verify:

1. `http://localhost:<bff-port>/` serves the Shell from the BFF origin.
2. Login redirects to the Apps Keycloak realm and returns to the Shell.
3. The header displays the authenticated tenant.
4. `/integration` lists only profiles belonging to that tenant.
5. Logout removes access and a subsequent profile request cannot use the old session.
6. A browser-supplied tenant header cannot change the effective tenant.

## Out of scope and follow-up

Profile mutations, detailed profile configuration forms, role-based navigation, tenant switching, observability dashboards for Backoffice, Compose deployment of the Backoffice BFF/Shell, and production-grade secret rotation remain separate iterations.
