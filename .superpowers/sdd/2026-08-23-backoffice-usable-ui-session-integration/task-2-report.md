# Task 2 report — complete OIDC session lifecycle

## Status

Implemented the OIDC session lifecycle and corrected its public-route wiring.

## Commit

Initial implementation: `937f235 feat(backoffice): complete OIDC session lifecycle`.

Public-route remediation: `4109900 fix(backoffice): expose public auth paths`.

## Changed files

- `backoffice/apps/bff/src/auth/auth.controller.ts`
- `backoffice/apps/bff/src/auth/auth.service.ts`
- `backoffice/apps/bff/src/auth/auth.module.ts`
- `backoffice/apps/bff/src/auth/session-types.ts`
- `backoffice/apps/bff/src/auth/auth.controller.spec.ts`
- `backoffice/apps/bff/src/auth/auth-callback.spec.ts`
- `backoffice/apps/bff/src/main.ts`
- `.superpowers/sdd/2026-08-23-backoffice-usable-ui-session-integration/task-2-review.md`

## Behaviour delivered

- Stores PKCE verifier and state during login.
- Validates callback state before exchanging the authorization code.
- Exchanges the code through `openid-client.authorizationCodeGrant` and stores tokens only in the server session.
- Returns a token-free session projection containing authentication state, tenant ID, and expiry.
- Destroys the session before logout redirect.
- Keeps the global `/api` prefix for non-auth routes while exposing `GET /auth/login`,
  `GET /auth/callback`, `GET /auth/session`, and `GET /auth/logout` as the approved
  public browser paths.

## Verification

- `git diff --check` was run.
- Tests were not run: the task owner explicitly requested no test commands and no npm/Nx execution. This prevents focused Jest and `nx test bff` verification in this task run.

## Public paths

Nest excludes the four `GET auth/*` controller routes from its global `api` prefix.
The deployed browser endpoints are therefore `/auth/login`, `/auth/callback`,
`/auth/session`, and `/auth/logout`. `BFF_PUBLIC_URL` remains the BFF origin (for
example, `http://localhost:4000`); the Keycloak redirect URI is
`${BFF_PUBLIC_URL}/auth/callback` and does not require an `/api` suffix.
