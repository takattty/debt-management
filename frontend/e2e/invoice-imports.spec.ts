import { readFile } from 'node:fs/promises';
import { expect, type Page } from '@playwright/test';
import { test } from './fixtures';

const password = process.env.LOCAL_SEED_PASSWORD ?? 'debt_local_demo_2026';

async function login(page: Page, email: string, url = '/') {
  await page.goto(url); await page.getByLabel('メールアドレス').fill(email); await page.getByLabel('パスワード').fill(password);
  await page.getByRole('button', { name: 'ログイン', exact: true }).click();
  await expect(page.getByRole('heading', { name: '顧客を登録' })).toBeVisible();
}

async function preview(page: Page, content: string) {
  await page.getByLabel('請求CSVファイル').setInputFiles({ name: 'invoices.csv', mimeType: 'text/csv', buffer: Buffer.from(content) });
  const response = page.waitForResponse((entry) => entry.url().endsWith('/api/imports/invoices/preview') && entry.request().method() === 'POST');
  await page.getByRole('button', { name: 'CSVをプレビュー', exact: true }).click();
  const stored = await response; expect(stored.status()).toBe(201); return stored.json();
}

test('invoice CSV repairs errors, confirms all rows and reloads server receivables through real API/DB', async ({ page, browser }) => {
  const prefix = `INV-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const code = `${prefix}-C`;
  const header = 'customer_code,invoice_number,issue_date,due_date,amount\n';
  await login(page, 'alpha-accountant@example.test');
  await page.getByLabel('顧客コード', { exact: false }).fill(code);
  await page.getByLabel('顧客名', { exact: false }).fill(`架空の請求先 ${prefix}`);
  await page.getByRole('button', { name: '顧客を登録', exact: true }).click();
  await expect(page.getByRole('cell', { name: code, exact: true })).toBeVisible();
  const customer = (await (await page.request.get('/api/customers')).json()).find((item: { code: string }) => item.code === code);
  await page.getByRole('button', { name: '請求CSV取込', exact: true }).click();
  const templateDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'テンプレートをダウンロード' }).click();
  const template = await templateDownload;
  expect(template.suggestedFilename()).toBe('invoices-template.csv');
  expect(await readFile((await template.path())!, 'utf8')).toBe('\uFEFFcustomer_code,invoice_number,issue_date,due_date,amount\r\n');

  const invalid = await preview(page, header + `${code},${prefix}-1,2026-10-30,2026-11-30,100000\n${code},${prefix}-2,2026-10-30,2026-02-30,abc\n${prefix}-MISSING,${prefix}-3,2026-10-30,2026-11-30,300000\n`);
  const errors = page.getByRole('table', { name: 'CSVエラー一覧' });
  await expect(errors).toContainText('abc');
  await expect(errors).toContainText('amount');
  await expect(errors).toContainText('due_date');
  await expect(errors).toContainText('customer_code');
  await expect(errors).toContainText('3');
  await expect(errors).toContainText('4');
  await expect(page.getByText(/まだ1件も登録されていません/)).toBeVisible();
  await expect(page.getByRole('button', { name: /確定して登録/ })).toBeDisabled();
  expect((await (await page.request.get(`/api/receivables?customerId=${customer.id}`)).json()).items).toHaveLength(0);
  const errorDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: '全エラーをCSVでダウンロード' }).click();
  const report = await errorDownload;
  const correction = await readFile((await report.path())!, 'utf8');
  expect(correction).toContain('abc'); expect(correction).toContain('customer_code');

  const repaired = await preview(page, header + [1, 2, 3].map((number) => `${code},${prefix}-${number},2026-10-30,2026-11-30,${number}00000`).join('\n') + '\n');
  expect(repaired.id).not.toBe(invalid.id);
  expect(repaired.status).toBe('PREVIEW_VALID');
  await expect(errors).toHaveCount(0);
  await expect(page.getByRole('table', { name: '請求CSVプレビュー' })).toContainText('300000');
  await expect(page.getByRole('button', { name: '全3件を確定して登録' })).toBeEnabled();
  await page.getByRole('button', { name: '全3件を確定して登録' }).click();
  await expect(page.getByRole('status')).toContainText('3件の請求を登録しました');
  await page.getByRole('button', { name: '債権一覧へ' }).click();
  await expect(page.getByRole('table', { name: '債権一覧' })).toContainText(`${prefix}-1`);
  await page.getByRole('combobox', { name: '顧客で絞り込む' }).selectOption(customer.id);
  await page.getByRole('button', { name: '条件で表示' }).click();
  await expect(page.getByRole('table', { name: '債権一覧' }).getByRole('row')).toHaveCount(4);
  await expect(page.getByRole('table', { name: '債権一覧' })).toContainText('100,000');
  const confirmed = (await (await page.request.get(`/api/receivables?customerId=${customer.id}`)).json()).items;
  expect(confirmed).toHaveLength(3);
  expect(confirmed.every((item: { amount: number; outstandingAmount: number }) => item.amount === item.outstandingAmount)).toBe(true);
  await page.reload();
  await page.getByRole('button', { name: '債権一覧', exact: true }).click();
  await expect(page.getByRole('table', { name: '債権一覧' })).toContainText(`${prefix}-3`);

  const otherContext = await browser.newContext(); const other = await otherContext.newPage();
  try {
    await login(other, 'beta-admin@example.test', new URL('/', page.url()).toString());
    expect((await other.request.get(`/api/imports/${repaired.id}`)).status()).toBe(404);
    expect((await other.request.get(`/api/imports/${invalid.id}/errors.csv`)).status()).toBe(404);
    expect((await (await other.request.get(`/api/receivables?customerId=${customer.id}`)).json()).items).toHaveLength(0);
  } finally { await otherContext.close(); }
});

test('customer changes between preview and confirmation invalidate all invoices and require a fresh preview', async ({ page, browser }) => {
  const prefix = `REF-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const code = `${prefix}-C`;
  const changedName = `更新後の架空商事 ${prefix}`;
  const csv = `customer_code,invoice_number,issue_date,due_date,amount\n${code},${prefix}-1,2026-10-30,2026-11-30,100000\n`;
  await login(page, 'alpha-accountant@example.test');
  await page.getByLabel('顧客コード', { exact: false }).fill(code);
  await page.getByLabel('顧客名', { exact: false }).fill(`架空の変更前商事 ${prefix}`);
  await page.getByRole('button', { name: '顧客を登録', exact: true }).click();
  await expect(page.getByRole('cell', { name: code, exact: true })).toBeVisible();
  const customer = (await (await page.request.get('/api/customers')).json()).find((item: { code: string }) => item.code === code);
  await page.getByRole('button', { name: '請求CSV取込', exact: true }).click();
  const first = await preview(page, csv);
  await expect(page.getByRole('button', { name: '全1件を確定して登録' })).toBeEnabled();
  const otherContext = await browser.newContext(); const other = await otherContext.newPage();
  try {
    await login(other, 'alpha-admin@example.test', new URL('/', page.url()).toString());
    await other.getByRole('button', { name: `顧客 ${code} を編集`, exact: true }).click();
    await other.getByLabel('編集中の顧客名', { exact: true }).fill(changedName);
    await other.getByRole('button', { name: '変更を保存' }).click();
    await expect(other.getByRole('cell', { name: changedName, exact: true })).toBeVisible();
    const rejected = page.waitForResponse((entry) => entry.url().endsWith(`/api/imports/${first.id}/confirm`) && entry.request().method() === 'POST');
    await page.getByRole('button', { name: '全1件を確定して登録' }).click();
    expect((await rejected).status()).toBe(409);
    await expect(page.getByRole('table', { name: 'CSVエラー一覧' })).toContainText(code);
    await expect(page.getByRole('table', { name: 'CSVエラー一覧' })).toContainText('customer_code');
    await expect(page.getByRole('button', { name: '全1件を確定して登録' })).toBeDisabled();
    expect((await (await page.request.get(`/api/receivables?customerId=${customer.id}`)).json()).items).toHaveLength(0);
    const fresh = await preview(page, csv); expect(fresh.id).not.toBe(first.id);
    await expect(page.getByRole('table', { name: '請求CSVプレビュー' })).toContainText(changedName);
    await page.getByRole('button', { name: '全1件を確定して登録' }).click();
    await expect(page.getByRole('status')).toContainText('1件の請求を登録しました');
    await page.getByRole('button', { name: '債権一覧へ' }).click();
    await expect(page.getByRole('table', { name: '債権一覧' })).toContainText(`${prefix}-1`);
    await expect(page.getByRole('table', { name: '債権一覧' })).toContainText(changedName);
  } finally { await otherContext.close(); }
});
