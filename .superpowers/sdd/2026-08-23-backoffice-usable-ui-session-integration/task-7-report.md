# Task 7 report — integration E2E and acceptance documentation

## Delivered

- Updated the existing Shell-to-Integration-MicroUI Playwright specification with an explicit remote-marker test name.
- Added root-route structural coverage for the semantic Backoffice Shell layout, absence of `app-nx-welcome`, anonymous session status, and the local `/auth/login` navigation action. The test does not enter Keycloak or use credentials.
- Added `docs/backoffice-usable-ui-acceptance.md`, a credential-free manual acceptance checklist covering safe configuration, startup, login/logout, tenant isolation, Integration Profiles list states, expected HTTP responses, and cleanup.
- Preserved the existing Chromium, Firefox, and WebKit Playwright project matrix without configuration changes.

## Verification

- Ran `npx nx e2e shell-e2e` from `backoffice/`.
- Result: blocked/failed with 4 passing and 8 failing browser tests. Chromium and WebKit served the generated Nx welcome page at `/`, so the new structural Shell assertions could not observe the committed Backoffice layout. The captured Playwright accessibility snapshot showed `Hello there, Welcome shell` rather than the expected header, navigation, and anonymous login action.
- The existing remote-marker scenario passed in Chromium and WebKit. Firefox experienced 30-second navigation/render timeouts, including the existing example and remote-marker scenarios.
- The Playwright configuration allows reuse of an existing server on ports 4201 and 4202. The observed stale Nx welcome page indicates that an already-running development server, rather than the committed Shell source, was reused. Per follow-up instruction, no server termination, configuration change, or rerun was performed.
- `git diff --check` was run before commit; it reported no whitespace errors (only the repository's LF-to-CRLF working-tree warning).

## Scope and credential handling

- Changed only Task 7 E2E specs, the required acceptance checklist, and this report.
- No browser matrix, application source, authentication route, or real credential/token was changed or recorded.

## Review follow-up

- `a7c463f fix(backoffice): make acceptance servers deterministic` sets both Playwright web servers to `reuseExistingServer: false` and documents the separate Integration MicroUI build/remote URL.
- The initial browser result remains unverified after this fix because Nx dependencies are unavailable; the acceptance review is PASS WITH NOTES rather than a passing-test claim.

## Root-artifact reconciliation

The root follow-up report's verification detail is preserved here: its deterministic-server configuration and startup documentation were reviewed statically without npm, Nx, or test commands; the Shell and Integration MicroUI remote use ports 4201 and 4202 respectively.
