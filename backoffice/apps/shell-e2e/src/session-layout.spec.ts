import { expect, test } from '@playwright/test';

test('root renders the semantic Shell layout instead of the Nx welcome component', async ({ page }) => {
  await page.goto('/');

  await expect(page.locator('header')).toContainText('Backoffice');
  await expect(page.getByRole('navigation', { name: 'Primary navigation' })).toBeVisible();
  await expect(page.locator('main')).toContainText('Welcome to Backoffice');
  await expect(page.locator('app-nx-welcome')).toHaveCount(0);
});

test('anonymous Shell presents a login action without requiring IdP credentials', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByText('You are not signed in')).toBeVisible();
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page).toHaveURL(/\/auth\/login$/);
});
