# Task 6 review

## Verdict

PASS WITH NOTES

## Findings

### 1. Resolved: static Shell serving is registered before Nest's 404 handler

`configureStaticShell(app)` now runs before `await app.init()` in production
and the focused test setup. This places the static assets and SPA fallback
before Nest registers its router and not-found handler, while preserving the
explicit `/api`, `/auth`, and `/bff/api` exclusions.

### 2. Resolved: `/integration` and `/bff/api/**` precedence contracts are tested

`static-shell-fallback.spec.ts` now asserts that `/integration` serves the
Shell HTML and that anonymous `GET /bff/api/v1/integration-profiles` remains a
JSON API response rather than receiving the SPA fallback.

## Scope and verification notes

- Reviewed only Task 6 against the approved spec, plan, brief, report, and
  the follow-up fix.
- No npm, Nx, or test commands were run for the follow-up, per instruction.
- Route behavior is covered by focused assertions but was not executed in this
  follow-up; this is the remaining verification note.
