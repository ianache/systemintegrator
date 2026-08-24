# Task 2 review — OIDC session lifecycle remediation

## Verdict

**PASS WITH NOTES**

The public-path finding is resolved by `4109900`. The BFF keeps its legacy global `/api`
prefix for other controllers while explicitly excluding the four approved
browser-facing authentication routes: `/auth/login`, `/auth/callback`,
`/auth/session`, and `/auth/logout`. This aligns the actual Nest route mapping
with the approved single-origin contract and with the existing Keycloak redirect
URI `${BFF_PUBLIC_URL}/auth/callback`.

## Verification

- Static inspection confirms `main.ts` excludes exactly the four `GET auth/*`
  paths from `app.setGlobalPrefix('api')`.
- `git diff --check` is run after the change.
- No npm or Nx commands were run, per the task constraint.

## Notes

- The previous service-boundary coverage gap for `authorizationCodeGrant`
  arguments and token/claim projection remains a test-coverage note. The
  follow-up instruction restricted this remediation to the global-prefix fix,
  so no test setup or additional test changes were made.
