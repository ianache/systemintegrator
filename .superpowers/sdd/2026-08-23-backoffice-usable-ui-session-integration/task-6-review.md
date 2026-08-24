# Task 6 review — serve Shell from BFF origin

## Verdict

PASS WITH NOTES

Commit `05aad12` resolves the blocking middleware-order defect: `configureStaticShell` is registered before `app.init()` in production and focused test setup, so `/` and `/integration` can reach the Shell fallback before Nest's 404 handler. The focused coverage now includes `/integration` HTML and anonymous `/bff/api/v1/integration-profiles` API precedence, while `/api`, `/auth`, and `/bff/api` remain excluded from fallback.

Nx/npm verification remains unavailable in this worktree; `git diff --check` was clean.

## Root-artifact reconciliation

The root review's additional summary is preserved here: static serving is registered before Nest's 404 handler, `/integration` returns Shell HTML, and the public `/bff/api/**` namespace retains API precedence rather than receiving the Shell fallback.
