import { defineConfig, devices } from '@playwright/test';
import { join, resolve } from 'node:path';

// For CI, you may want to set BASE_URL to the deployed application.
const baseURL = process.env['BASE_URL'] || 'http://localhost:4201';

const projectRoot = import.meta.dirname;
const workspaceRoot = resolve(projectRoot, '..', '..');
const outputBase = join(workspaceRoot, 'dist', '.playwright', 'apps', 'shell-e2e');

/**
 * See https://playwright.dev/docs/test-configuration.
 *
 * Generated as a .mts file so Node forces ESM regardless of workspace
 * `type`. Playwright routes `.mts` through its ESM loader (dynamic import,
 * bypassing the pirates CJS-compile path), and Nx's native TS strip loads
 * `.mts` directly. Playwright's configLoader auto-discovers
 * `playwright.config.mts` via its extension list
 * (.ts/.js/.mts/.mjs/.cts/.cjs).
 *
 * The Nx-recommended defaults (`nxE2EPreset`) are inlined rather than
 * imported: under Playwright's ESM loader, `@nx/playwright/preset` pulls in
 * `nx/dist/src/native/index.js`, which does `delete require.cache[...]`. In
 * that loader `require.cache` is undefined, so loading the config crashes with
 * `TypeError: Cannot convert undefined or null to object`. Keeping the config
 * free of Nx imports keeps `nx e2e shell-e2e` runnable.
 */
export default defineConfig({
  testDir: './src',
  outputDir: join(outputBase, 'test-output'),
  /* Run tests in files in parallel */
  fullyParallel: true,
  /* Fail the build on CI if you accidentally left test.only in the source code. */
  forbidOnly: !!process.env.CI,
  /* Retry on CI only */
  retries: process.env.CI ? 2 : 0,
  /* Opt out of parallel tests on CI. */
  workers: process.env.CI ? 1 : undefined,
  reporter: [
    ['list'],
    ['html', { outputFolder: join(outputBase, 'playwright-report'), open: 'never' }],
  ],
  /* Shared settings for all the projects below. See https://playwright.dev/docs/api/class-testoptions. */
  use: {
    baseURL,
    /* Collect trace when retrying the failed test. See https://playwright.dev/docs/trace-viewer */
    trace: 'on-first-retry',
  },
  /*
   * Run both federated dev servers before starting the tests: the Shell
   * (dynamic host, 4201) and the integration MicroUI remote (4202). The Shell
   * resolves the remote at runtime from the URL in `app.routes.ts`, so the
   * remote has to be reachable for the `/integration` route to render.
   */
  webServer: [
    {
      command: 'npx nx run integration-mfe:serve',
      url: 'http://localhost:4202/remoteEntry.json',
      reuseExistingServer: false,
      timeout: 180_000,
      cwd: workspaceRoot,
    },
    {
      command: 'npx nx run shell:serve',
      url: 'http://localhost:4201',
      reuseExistingServer: false,
      timeout: 180_000,
      cwd: workspaceRoot,
    },
  ],
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
    },
  ],
});
