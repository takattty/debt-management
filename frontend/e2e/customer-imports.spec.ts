import { statusFeedback } from './status-feedback';
import { readFile } from 'node:fs/promises';
import { expect, type Page } from '@playwright/test';
import { test } from './fixtures';

const password = process.env.LOCAL_SEED_PASSWORD ?? 'debt_local_demo_2026';

async function login(page: Page, email: string) {
  await page.getByLabel('メールアドレス').fill(email);
  await page.getByLabel('パスワード').fill(password);
  await page.getByRole('button', { name: 'ログイン', exact: true }).click();
  await expect(page.getByRole('heading', { name: '顧客を登録' })).toBeVisible();
}

async function preview(page: Page, contents: string) {
  await page.getByLabel('顧客CSVファイル').setInputFiles({ name: 'customers.csv', mimeType: 'text/csv', buffer: Buffer.from(contents, 'utf8') });
  const response = page.waitForResponse((entry) => entry.url().endsWith('/api/imports/customers/preview') && entry.request().method() === 'POST');
  await page.getByRole('button', { name: 'CSVをプレビュー', exact: true }).click();
  const stored = await response;
  expect(stored.status()).toBe(201);
  await expect(page.getByRole('heading', { name: 'プレビュー内容' })).toBeVisible();
  return stored.json();
}

test('customer CSV repairs an existing-code error, downloads guidance and confirms all rows through real API/DB', async ({ page, browser }) => {
  const prefix = `CSV-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const existingCode = `${prefix}-EXIST`;
  const newCode = `${prefix}-A`;
  const anotherCode = `${prefix}-B`;
  const firstName = `架空商事 ${prefix},第一`;
  const secondName = `架空商事 ${prefix}𠮷第二`;
  await page.goto('/');
  await login(page, 'alpha-admin@example.test');
  await page.getByLabel('顧客コード', { exact: false }).fill(existingCode);
  await page.getByLabel('顧客名', { exact: false }).fill(`既存の架空顧客 ${prefix}`);
  await page.getByRole('button', { name: '顧客を登録', exact: true }).click();
  await expect(statusFeedback(page, '登録しました')).toContainText('登録しました');
  await page.getByRole('button', { name: '顧客CSV取込', exact: true }).click();

  const templateDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'テンプレートをダウンロード' }).click();
  const template = await templateDownload;
  expect(template.suggestedFilename()).toBe('customers-template.csv');
  expect(await readFile((await template.path())!, 'utf8')).toBe('\uFEFFcustomer_code,customer_name\r\n');

  const invalid = await preview(page, `\uFEFFcustomer_code,customer_name\r\n${newCode},新規の架空顧客\r\n${existingCode},上書きしない名称\r\n`);
  const errorTable = page.getByRole('table', { name: 'CSVエラー一覧' });
  await expect(errorTable).toContainText(existingCode);
  await expect(errorTable).toContainText('3');
  await expect(errorTable).toContainText('登録');
  await expect(page.getByRole('button', { name: '全2件を確定して登録' })).toBeDisabled();
  await expect(page.getByText(/まだ1件も登録されていません/)).toBeVisible();
  const before = await (await page.request.get('/api/customers')).json();
  expect(before.some((customer: { code: string }) => customer.code === newCode)).toBe(false);
  expect(before.find((customer: { code: string }) => customer.code === existingCode).name).toBe(`既存の架空顧客 ${prefix}`);

  const errorDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: '全エラーをCSVでダウンロード' }).click();
  const report = await errorDownload;
  expect(report.suggestedFilename()).toBe('import-errors.csv');
  const guidance = await readFile((await report.path())!, 'utf8');
  expect(guidance.startsWith('\uFEFF')).toBe(true);
  expect(guidance).toContain(existingCode);
  expect(guidance).toContain('customer_code');

  const repaired = await preview(page, `customer_code,customer_name\n${newCode},"${firstName}"\n${anotherCode},${secondName}\n`);
  expect(repaired.id).not.toBe(invalid.id);
  await expect(page.getByRole('table', { name: 'CSVエラー一覧' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '全2件を確定して登録' })).toBeEnabled();
  await page.getByRole('button', { name: '全2件を確定して登録' }).click();
  await expect(statusFeedback(page, '2件の顧客を登録しました')).toContainText('2件の顧客を登録しました');
  await page.getByRole('button', { name: '顧客一覧へ' }).click();
  await expect(page.getByRole('cell', { name: firstName, exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: secondName, exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('cell', { name: newCode, exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: anotherCode, exact: true })).toBeVisible();
  const after = await (await page.request.get('/api/customers')).json();
  expect(after.filter((customer: { code: string }) => customer.code === newCode)).toHaveLength(1);
  expect(after.filter((customer: { code: string }) => customer.code === anotherCode)).toHaveLength(1);

  const otherContext = await browser.newContext();
  const other = await otherContext.newPage();
  try {
    await other.goto(new URL('/', page.url()).toString());
    await login(other, 'beta-admin@example.test');
    await expect(other.getByRole('cell', { name: newCode, exact: true })).toHaveCount(0);
    expect((await other.request.get(`/api/imports/${repaired.id}`)).status()).toBe(404);
    expect((await other.request.get(`/api/imports/${invalid.id}/errors.csv`)).status()).toBe(404);
  } finally { await otherContext.close(); }
});

test('accountant confirms customer CSV independently and expired session clears the import without writing', async ({ page }) => {
  const code = `CSV-ACC-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  await page.goto('/');
  await login(page, 'alpha-accountant@example.test');
  await expect(page.getByText('経理担当者', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '顧客CSV取込', exact: true }).click();
  await preview(page, `customer_code,customer_name\n${code},架空経理CSV顧客\n`);
  await page.getByRole('button', { name: '全1件を確定して登録' }).click();
  await expect(statusFeedback(page, '1件の顧客を登録しました')).toContainText('1件の顧客を登録しました');
  await page.getByRole('button', { name: '顧客一覧へ' }).click();
  await expect(page.getByRole('cell', { name: code, exact: true })).toBeVisible();
  await page.getByRole('button', { name: '顧客CSV取込', exact: true }).click();
  await page.getByLabel('顧客CSVファイル').setInputFiles({ name: 'expired.csv', mimeType: 'text/csv', buffer: Buffer.from(`customer_code,customer_name\n${code}-X,失効後の架空顧客\n`) });

  const session = await (await page.request.get('/api/session')).json();
  expect((await page.request.post('/api/logout', { headers: { [session.csrfHeaderName]: session.csrfToken } })).status()).toBe(204);
  await page.getByRole('button', { name: 'CSVをプレビュー', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('ログインの有効期限が切れました');
  await expect(page.getByLabel('顧客CSVファイル')).toHaveCount(0);
  await expect(page.getByRole('table')).toHaveCount(0);
  await expect(page.getByLabel('パスワード')).toHaveValue('');
  await page.getByLabel('メールアドレス').fill('alpha-accountant@example.test');
  await page.getByLabel('パスワード').fill(password);
  await page.getByRole('button', { name: 'ログイン', exact: true }).click();
  await expect(page.getByRole('cell', { name: code, exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: `${code}-X`, exact: true })).toHaveCount(0);
});
