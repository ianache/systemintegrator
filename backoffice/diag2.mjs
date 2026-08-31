import { chromium } from 'playwright';
const OUT = 'C:\\Users\\ianache\\AppData\\Local\\Temp\\claude\\C--Users-ianache-Desktop-DATA-01-DOCUMENTOS-03-PERSONAL-12-systemintegrator\\fc1f3471-bb76-4270-9f5d-3528c7142ece\\scratchpad';
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
page.on('console', (m) => { if (m.type() === 'error') console.log('[console error]', m.text()); });

await page.goto('http://localhost:4200', { waitUntil: 'networkidle', timeout: 30000 });
const loginLink = page.getByText('Iniciar sesión', { exact: true });
if (await loginLink.count() > 0) {
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle', timeout: 30000 }).catch(() => {}),
    loginLink.click(),
  ]);
}
if (page.url().includes('/realms/')) {
  await page.fill('#username', 'superset');
  await page.fill('#password', 'superset');
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle', timeout: 30000 }).catch(() => {}),
    page.click('#kc-login'),
  ]);
}
await page.waitForTimeout(1500);
console.log('URL:', page.url());
console.log('app-integration-tabs count:', await page.locator('app-integration-tabs').count());
console.log('Flows text count:', await page.getByText('Flows', { exact: false }).count());
await page.screenshot({ path: `${OUT}\\diag2.png`, fullPage: true });
await browser.close();
