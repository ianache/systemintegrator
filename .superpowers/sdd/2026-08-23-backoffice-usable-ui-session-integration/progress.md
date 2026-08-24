# SDD ledger — plan: docs/superpowers/plans/2026-08-23-backoffice-usable-ui-session-integration.md

Execution is isolated in `C:\\bw-ui` on branch `feat/backoffice-usable-ui-session-integration`.

## Rulings

1. The pre-existing unrelated `backoffice/nx.json` change on `main` is out of scope and must remain untouched.
2. The approved spec is authoritative when the plan shorthand conflicts with it. Public auth paths are `/auth/**`; the profile proxy is `/bff/api/**`.
3. Each task is implemented, tested, committed, and reviewed before the next task.
4. Task 4 scope includes `backoffice/apps/shell/src/app/app.routes.ts`, omitted from the plan file list but required by its explicit root and wildcard route acceptance criteria.
5. Task 5 scope includes `integration-profile-list.component.spec.ts`, omitted from the plan file list but required by its explicit component-state test acceptance criteria.
6. Task 7 scope includes `backoffice/apps/shell-e2e/playwright.config.mts`, omitted from the plan file list but required to prevent stale-server reuse from invalidating the browser acceptance run.
7. The approved spec's public `GET /bff/api/v1/integration-profiles` contract wins over the plan shorthand `GET /api/bff/api/v1/integration-profiles`. Nest excludes only that exact GET path from its global `/api` prefix; legacy `/api/**` routes and all four public `/auth/**` exclusions remain unchanged.

## Status

| Task | Status | Notes |
|---|---|---|
| 1. BFF typed config/session seams | completed | Commits `42c2deb`, `ff11469`; review PASS WITH NOTES; Nx verification blocked by missing `node_modules/nx` |
| 2. OIDC session lifecycle | completed | Commits `937f235`, `4109900`; review PASS WITH NOTES; Nx verification blocked by missing dependencies |
| 3. Authenticated Gateway proxy | completed | Commits `872b4fa`, `ff9bb6c`; review PASS WITH NOTES; Nx verification blocked/unavailable |
| 4. Shell layout/session UI | completed | Commits `072a093`, `fbda375`; review PASS WITH NOTES; tests/build unverified due Nx environment |
| 5. Integration Profiles MicroUI | completed | Commits `f7fb853`, `6497130`; review PASS WITH NOTES; HTTP DTO confirms `syncDirection`; Nx verification unavailable |
| 6. BFF serves built Shell single origin | completed | Commits `7caa21e`, `05aad12`; review PASS WITH NOTES; runtime tests/build unverified due Nx environment |
| 7. E2E/acceptance docs | completed | Commits `b1e2205`, `a7c463f`; review PASS WITH NOTES; deterministic servers fixed, full E2E rerun pending Nx recovery |
| 8. Full verification | completed | Quick handoff verification: `git diff --check` passed and worktree is clean; Nx missing, Maven/full E2E/build/unit verification remains unexecuted and documented in task-8-report.md |
