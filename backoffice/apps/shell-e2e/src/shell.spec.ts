import { test, expect } from '@playwright/test';

test('integration route renders the Native Federation remote marker', async ({ page }) => {
  await page.goto('/integration');
  // The Shell resolves the remote at runtime: it fetches
  // http://localhost:4202/remoteEntry.json, extends the import map, then imports
  // the exposed `./Routes` module. Against a cold dev server that first load is
  // well past Playwright's 5s default (Firefox is the slowest here), so give the
  // assertion room. The assertion itself still only passes if the remote really
  // rendered — this text exists only in integration-mfe.
  await expect(page.getByTestId('integration-mfe-loaded').getByRole('heading', { name: 'Integration Profiles' })).toBeVisible({ timeout: 30_000 });
});
