import { expect, type Page } from '@playwright/test';
import { test } from './fixtures';

const password = process.env.LOCAL_SEED_PASSWORD ?? 'debt_local_demo_2026';

async function login(page: Page, email: string) {
  await page.getByLabel('メールアドレス').fill(email);
  await page.getByLabel('パスワード').fill(password);
  await page.getByRole('button', { name: 'ログイン', exact: true }).click();
  await expect(page.getByRole('heading', { name: '顧客を登録' })).toBeVisible();
}

test('customer registration survives cookie-session reload, rejects duplicates and isolates tenants through real API/DB', async ({ page, browser }) => {
  const unique = `E2E-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const code = unique + '𠮷'.repeat(50 - Array.from(unique).length);
  const customerName = unique + '𠮷'.repeat(200 - Array.from(unique).length);
  await page.goto('/');
  await login(page, 'alpha-admin@example.test');
  await expect(page.getByText('管理者', { exact: true })).toBeVisible();
  await expect(page.getByText('alpha-admin@example.test', { exact: true })).toBeVisible();
  const cookie = (await page.context().cookies()).find((entry) => entry.name === 'JSESSIONID');
  expect(cookie?.httpOnly).toBe(true);
  expect(cookie?.sameSite).toBe('Lax');
  expect(await page.evaluate(() => document.cookie)).not.toContain('JSESSIONID');
  await page.getByLabel('顧客コード', { exact: false }).fill(code);
  await page.getByLabel('顧客名', { exact: false }).fill(customerName);
  await page.getByRole('button', { name: '顧客を登録', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('登録しました');
  await expect(page.getByRole('cell', { name: customerName, exact: true })).toBeVisible();

  await page.reload();
  await expect(page.getByRole('cell', { name: customerName, exact: true })).toBeVisible();
  await expect(page.getByLabel('パスワード')).toHaveCount(0);
  await page.getByLabel('顧客コード', { exact: false }).fill(code);
  await page.getByLabel('顧客名', { exact: false }).fill(customerName);
  await page.getByRole('button', { name: '顧客を登録', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('登録済み');
  await expect(page.getByRole('cell', { name: customerName, exact: true })).toHaveCount(1);

  await page.getByRole('button', { name: 'ログアウト', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'ワークスペースにログイン' })).toBeVisible();
  expect((await page.request.get('/api/customers')).status()).toBe(401);
  await page.reload();
  await expect(page.getByLabel('メールアドレス')).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);

  const otherContext = await browser.newContext();
  const other = await otherContext.newPage();
  await other.goto(new URL('/', page.url()).toString());
  await login(other, 'beta-admin@example.test');
  await expect(other.getByRole('cell', { name: customerName, exact: true })).toHaveCount(0);
  await other.getByLabel('顧客コード', { exact: false }).fill(code);
  await other.getByLabel('顧客名', { exact: false }).fill(`別企業 ${code}`);
  await other.getByRole('button', { name: '顧客を登録', exact: true }).click();
  await expect(other.getByRole('status')).toContainText('登録しました');
  await otherContext.close();
});

test('wrong password is rejected, accountant can register and stale session clears data without writing', async ({ page }) => {
  const code = `AUTH-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const expiredCode = `${code}-EXPIRED`;
  await page.goto('/');
  await page.getByLabel('メールアドレス').fill('alpha-accountant@example.test');
  await page.getByLabel('パスワード').fill('a-deliberately-wrong-password');
  await page.getByRole('button', { name: 'ログイン', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('メールアドレスまたはパスワードを確認');
  await expect(page.getByRole('button', { name: '顧客を登録', exact: true })).toHaveCount(0);
  await login(page, 'alpha-accountant@example.test');
  await expect(page.getByText('経理担当者', { exact: true })).toBeVisible();
  await page.getByLabel('顧客コード', { exact: false }).fill(code);
  await page.getByLabel('顧客名', { exact: false }).fill(`架空経理顧客 ${code}`);
  await page.getByRole('button', { name: '顧客を登録', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('登録しました');

  // Another tab logs out at the server while this UI still holds its old CSRF state.
  const sessionResponse = await page.request.get('/api/session');
  const session = await sessionResponse.json();
  expect((await page.request.post('/api/logout', { headers: { [session.csrfHeaderName]: session.csrfToken } })).status()).toBe(204);
  expect((await page.request.get('/api/session')).status()).toBe(401);
  await page.getByLabel('顧客コード', { exact: false }).fill(expiredCode);
  await page.getByLabel('顧客名', { exact: false }).fill('失効後の架空顧客');
  await page.getByRole('button', { name: '顧客を登録', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('ログインの有効期限が切れました');
  await expect(page.getByRole('table')).toHaveCount(0);
  await expect(page.getByLabel('パスワード')).toHaveValue('');
  await login(page, 'alpha-accountant@example.test');
  await expect(page.getByRole('cell', { name: code, exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: expiredCode, exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => localStorage.length)).toBe(0);
});
