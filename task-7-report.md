# Task 7 follow-up report

## Review fixes applied

- Set `reuseExistingServer: false` for both Playwright web servers: the Shell
  on port 4201 and the Integration MicroUI remote on port 4202. Each E2E run
  now starts its configured servers instead of accepting a potentially stale
  UI from an existing process.
- Preserved the Chromium, Firefox, and WebKit project matrix.
- Clarified the manual single-origin acceptance startup: Task 6 packages only
  the built Shell at `dist/apps/bff/shell-static`; the separately built remote
  remains at `dist/apps/integration-mfe/browser` and must be served at
  `http://localhost:4202/remoteEntry.json` for the Shell's `/integration`
  route.

## Verification note

No npm, Nx, or test commands were run, as directed. The follow-up was checked
by reviewing the Playwright configuration and the documented Task 6 packaging
paths.
