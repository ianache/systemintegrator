# Task 5 report — read-only Integration Profiles MicroUI

## Delivered

- Added the `IntegrationProfile` model with `id`, `businessDomain`, `externalSource`, `syncDirection`, `active`, and `version`.
- Added `IntegrationProfileService.list()`, which uses the same-origin `/bff/api/v1/integration-profiles` endpoint.
- Added an accessible, read-only Integration Profiles table with loading, populated, empty, session-expired/forbidden, and unavailable states.
- Added a native `Retry` button to the unavailable state. It returns the component to loading, re-invokes the same profile request, and resolves to the normal populated or empty state.
- Maps `401` and `403` responses to a generic session message and redirects to the Shell login flow at `/auth/login`; no backend response details are rendered.
- Verified the actual HTTP response DTO at `application/src/main/java/com/cl2/integration/adapter/in/web/dto/IntegrationProfileResponse.java`: its record component is `SyncDirection syncDirection`, so the JSON response field is `syncDirection`. The frontend model, template, and request fixtures already use the correct API field; no `direction` remapping is needed.
- Preserved the Native Federation route contract: `entry.routes.ts` remains `''` to `IntegrationRootComponent`, and the existing `integration-mfe-loaded` marker remains available.
- Added service coverage plus component-state coverage for loading, populated, empty, `401`, `403`, and `502` responses, including the `502` retry flow: unavailable alert → Retry click → loading/new GET → empty state.

## TDD and verification

- The focused retry component coverage was updated before the retry implementation. Against the prior implementation it would fail because the unavailable branch contained no button.
- Per instruction, no npm or Nx commands were run. A direct Angular CLI test attempt (`node node_modules/@angular/cli/bin/ng.js test integration-mfe --watch=false`) was blocked before test discovery because the installed Node.js is v22.15.0 while Angular CLI requires v22.22.3 or newer.
- `git diff --check` is run as part of the final review before commit.

## Scope

- Source changes are limited to the Task 5 Integration MicroUI files and the explicitly requested report.
- No profile mutation, tenant switching, edit action, browser token handling, or federation-route change was introduced.
