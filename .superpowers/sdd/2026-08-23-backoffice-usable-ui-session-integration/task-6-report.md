# Task 6 report — serve built Shell from BFF single origin

## Delivered

- Added BFF static Shell configuration that serves files from `shell-static` and falls back to its `index.html` for GET/HEAD SPA routes.
- Keeps `/auth/**`, `/api/**`, and `/bff/api/**` outside the SPA fallback, preserving the approved public auth endpoints and API-route precedence.
- Registers the static assets and fallback before Nest initializes its router and not-found handler in both production and focused test setup.
- Configured the BFF webpack build to copy `dist/apps/shell/browser` into `dist/apps/bff/shell-static`.
- Added HTTP-level coverage for root and `/integration` HTML serving, the prefixed BFF API response, an anonymous `/bff/api/v1/integration-profiles` API response, and an unknown auth route not receiving Shell HTML.
- Used the existing Nest Express static-asset capability; no new dependency, manifest, or lockfile update was required.

## Verification

- No test, npm, or Nx commands were run for this follow-up, per instruction.
- Source and documentation changes were inspected with `git diff --check` before commit.

## Scope

- Changed only BFF static-serving startup order and its focused test, plus this required task report and review.
- Auth controllers and gateway/API controllers were not modified.
