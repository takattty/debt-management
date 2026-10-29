import { expect, test, type Page } from '@playwright/test';

async function login(page: Page, username: string) {
  await page.getByLabel('ユーザー名').fill(username);
  await page.getByLabel('パスワード').fill('debt_local');
  await page.getByRole('button', { name: 'ログイン', exact: true }).click();
  await expect(page.getByRole('heading', { name: '顧客を登録' })).toBeVisible();
}

test('customer registration survives reload, rejects duplicates and isolates tenants through real API/DB', async ({ page, browser }) => {
  const unique = `E2E-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const code = unique + '𠮷'.repeat(50 - Array.from(unique).length);
  const customerName = unique + '𠮷'.repeat(200 - Array.from(unique).length);
  await page.goto('/');
  await login(page, 'alpha-admin');
  await page.getByLabel('顧客コード', { exact: false }).fill(code);
  await page.getByLabel('顧客名', { exact: false }).fill(customerName);
  await page.getByRole('button', { name: '顧客を登録', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('登録しました');
  await expect(page.getByRole('cell', { name: customerName, exact: true })).toBeVisible();

  await page.reload();
  await login(page, 'alpha-admin');
  await expect(page.getByRole('cell', { name: customerName, exact: true })).toBeVisible();
  await page.getByLabel('顧客コード', { exact: false }).fill(code);
  await page.getByLabel('顧客名', { exact: false }).fill(customerName);
  await page.getByRole('button', { name: '顧客を登録', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('登録済み');
  await expect(page.getByRole('cell', { name: customerName, exact: true })).toHaveCount(1);

  await page.getByRole('button', { name: 'ログアウト', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'ワークスペースにログイン' })).toBeVisible();
  expect((await page.request.get('/api/customers')).status()).toBe(401);

  const otherContext = await browser.newContext();
  const other = await otherContext.newPage();
  await other.goto(new URL('/', page.url()).toString());
  await login(other, 'beta-admin');
  await expect(other.getByRole('cell', { name: customerName, exact: true })).toHaveCount(0);
  await other.getByLabel('顧客コード', { exact: false }).fill(code);
  await other.getByLabel('顧客名', { exact: false }).fill(`別企業 ${code}`);
  await other.getByRole('button', { name: '顧客を登録', exact: true }).click();
  await expect(other.getByRole('status')).toContainText('登録しました');
  await otherContext.close();
});
