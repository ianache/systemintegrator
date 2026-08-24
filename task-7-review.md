# Task 7 review

## Verdict

PASS WITH NOTES

## Findings

### 1. Resolved: acceptance servers are deterministic

Both `webServer` entries in `backoffice/apps/shell-e2e/playwright.config.mts`
set `reuseExistingServer: false`. Playwright therefore starts the configured
Integration MicroUI remote and Shell for each run, avoiding stale UI while
retaining the Chromium, Firefox, and WebKit matrix.

### 2. Resolved: manual startup documents remote availability

The acceptance checklist now builds `integration-mfe` and explains that Task 6
copies only the Shell into the BFF's `shell-static` output. The separately
packaged remote at `dist/apps/integration-mfe/browser` is served at
`http://localhost:4202/remoteEntry.json`, matching the Shell federation route.

## Scope and verification notes

- Reviewed only the Task 7 follow-up fixes.
- No npm, Nx, or test commands were run, per instruction.
- The configuration and documentation changes were inspected statically; a
  browser run remains intentionally unexecuted.
