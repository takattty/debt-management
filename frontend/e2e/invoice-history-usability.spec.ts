import { expect, type Page } from '@playwright/test';
import { test } from './fixtures';
import { loginInvoiceUser, sessionHeaders } from './invoice-fixtures';
import type { Customer, ImportBatch, InvoiceHistory, Receivable } from '../src/api';
import type { components } from '../src/generated/schema';

test.use({ timezoneId: 'America/Los_Angeles' });

async function importInvoices(page: Page, content: string) {
  const headers = await sessionHeaders(page);
  const response = await page.request.post('/api/imports/invoices/preview', { headers, multipart: { file: { name: 'history-fixture.csv', mimeType: 'text/csv', buffer: Buffer.from('customer_code,invoice_number,issue_date,due_date,amount\n' + content) } } });
  expect(response.status()).toBe(201); const batch: ImportBatch = await response.json(); expect(batch.status).toBe('PREVIEW_VALID');
  const confirmed = await page.request.post(`/api/imports/${batch.id}/confirm`, { headers, data: { version: batch.version } }); expect(confirmed.status()).toBe(200);
}
function expectedJst(instant: string) {
  const shifted = new Date(Date.parse(instant) + 9 * 60 * 60 * 1000).toISOString();
  return `${shifted.slice(0, 19).replace('T', ' ').replaceAll('-', '/')}（日本時間 / JST）`;
}
async function receivables(page: Page, customerId: string, status = 'ALL'): Promise<Receivable[]> {
  const response = await page.request.get(`/api/receivables?customerId=${customerId}&status=${status}&limit=100`); expect(response.status()).toBe(200); return (await response.json()).items;
}

test('cancelled invoices remain discoverable through state/customer/date pages and show actor email and JST originals without copying an ID', async ({ page, browser }) => {
  const prefix = `HIST-${Date.now()}-${Math.floor(Math.random() * 100000)}`; await loginInvoiceUser(page);
  for (const suffix of ['OLD', 'OWN']) {
    await page.getByLabel('顧客コード', { exact: false }).fill(`${prefix}-${suffix}`); await page.getByLabel('顧客名', { exact: false }).fill(`架空の履歴確認先 ${prefix}-${suffix}`);
    await page.getByRole('button', { name: '顧客を登録', exact: true }).click(); await expect(page.getByRole('cell', { name: `${prefix}-${suffix}`, exact: true })).toBeVisible();
  }
  const customers: Customer[] = await (await page.request.get('/api/customers')).json(); const old = customers.find((item) => item.code === `${prefix}-OLD`)!; const own = customers.find((item) => item.code === `${prefix}-OWN`)!;
  await importInvoices(page, Array.from({ length: 52 }, (_, index) => `${old.code},${prefix}-OLD-${index + 1},2025-10-30,2025-11-30,10000`).join('\n') + '\n');
  await importInvoices(page, Array.from({ length: 52 }, (_, index) => `${own.code},${prefix}-CANCEL-${index + 1},2026-10-30,2026-11-30,100000`).join('\n') + `\n${own.code},${prefix}-ACTIVE,2026-10-30,2026-11-30,110000\n${own.code},${prefix}-LATE,2026-10-30,2026-12-31,120000\n`);
  const preserved = await receivables(page, old.id); expect(preserved).toHaveLength(52); const headers = await sessionHeaders(page);
  for (const invoice of await receivables(page, own.id)) if (invoice.invoiceNumber.includes('-CANCEL-')) {
    const cancelled = await page.request.post(`/api/invoices/${invoice.id}/cancel`, { headers, data: { version: invoice.version, reason: '架空の取消済み一覧の確認' } }); expect(cancelled.status()).toBe(200);
  }
  await page.getByRole('button', { name: '債権一覧', exact: true }).click(); const list = page.getByRole('table', { name: '債権一覧', exact: true });
  await expect(page.getByRole('combobox', { name: '請求の状態', exact: true })).toHaveValue('ACTIVE'); await expect(list).not.toContainText(`${prefix}-CANCEL-`);
  await page.getByRole('combobox', { name: '請求の状態', exact: true }).selectOption('CANCELLED'); await page.getByRole('combobox', { name: '顧客で絞り込む', exact: true }).selectOption(own.id);
  await page.getByLabel('支払期日（開始）', { exact: true }).fill('2026-11-30'); await page.getByLabel('支払期日（終了）', { exact: true }).fill('2026-11-30');
  const firstResponse = page.waitForResponse((entry) => entry.url().includes('/api/receivables?') && new URL(entry.url()).searchParams.get('status') === 'CANCELLED'); await page.getByRole('button', { name: '条件で表示', exact: true }).click();
  const first: components['schemas']['ReceivablesPage'] = await (await firstResponse).json(); expect(first.items).toHaveLength(50); expect(first.nextCursor).not.toBeNull(); await expect(list.getByRole('row')).toHaveCount(51); await expect(list.getByRole('cell', { name: '￥0', exact: true })).toHaveCount(50); await expect(list.getByRole('cell', { name: '￥100,000', exact: true })).toHaveCount(50);
  await page.getByRole('combobox', { name: '請求の状態', exact: true }).selectOption('ACTIVE'); await page.getByLabel('支払期日（終了）', { exact: true }).fill('2026-12-31');
  const nextResponse = page.waitForResponse((entry) => entry.url().includes('/api/receivables?') && new URL(entry.url()).searchParams.has('cursor')); await page.getByRole('button', { name: '次の50件', exact: true }).click();
  const next = await nextResponse; const query = new URL(next.url()).searchParams; expect(query.get('status')).toBe('CANCELLED'); expect(query.get('customerId')).toBe(own.id); expect(query.get('dueTo')).toBe('2026-11-30'); expect(query.get('cursor')).toBe(first.nextCursor);
  const second: components['schemas']['ReceivablesPage'] = await next.json(); expect(second.items).toHaveLength(2); await expect(list.getByRole('row')).toHaveCount(3); await expect(page.getByRole('button', { name: '次の50件', exact: true })).toBeDisabled();
  for (const item of first.items) await expect(list.getByRole('cell', { name: item.invoiceNumber, exact: true })).toHaveCount(0);
  const original = second.items[0]; await list.getByRole('button', { name: `請求 ${original.invoiceNumber} の履歴`, exact: true }).click(); const history = page.getByRole('table', { name: '請求履歴', exact: true }); await expect(history).toContainText('登録時の原本'); await expect(history).toContainText('架空の取消済み一覧の確認');
  const revisions: InvoiceHistory[] = (await (await page.request.get(`/api/invoices/${original.id}/history`)).json()).items;
  for (const revision of revisions) { expect(revision.actorEmail).toBe('alpha-accountant@example.test'); await expect(history.getByText(expectedJst(revision.occurredAt), { exact: true })).toHaveCount(revisions.filter((item) => expectedJst(item.occurredAt) === expectedJst(revision.occurredAt)).length); }
  await expect(history.getByRole('cell').filter({ hasText: '担当者：alpha-accountant@example.test' })).toHaveCount(revisions.length); await expect(history).toContainText('2026-10-30'); await expect(history).toContainText('2026-11-30'); await expect(page.getByRole('button', { name: '訂正を保存', exact: true })).toBeDisabled();
  await history.getByText('監査用の担当者ID', { exact: true }).first().click(); await expect(history.locator('code').first()).toHaveText(revisions[0].actorUserId);
  await page.getByRole('button', { name: '請求を閉じる', exact: true }).click(); await page.getByRole('combobox', { name: '請求の状態', exact: true }).selectOption('ALL');
  const allResponse = page.waitForResponse((entry) => entry.url().includes('/api/receivables?') && new URL(entry.url()).searchParams.get('status') === 'ALL'); await page.getByRole('button', { name: '条件で表示', exact: true }).click(); expect(new URL((await allResponse).url()).searchParams.has('cursor')).toBe(false);
  await page.getByRole('button', { name: '次の50件', exact: true }).click(); await expect(list.getByRole('row')).toHaveCount(5); await expect(list).toContainText(`${prefix}-LATE`); await expect(list).toContainText('2026-12-31');
  await page.getByRole('combobox', { name: '請求の状態', exact: true }).selectOption('ACTIVE'); await page.getByRole('button', { name: '条件で表示', exact: true }).click(); await expect(list.getByRole('row')).toHaveCount(3);
  await list.getByRole('button', { name: `請求 ${prefix}-ACTIVE の訂正・履歴`, exact: true }).click(); await page.getByLabel('取消理由', { exact: true }).fill('架空の有効一覧からの取消'); await page.getByRole('checkbox', { name: /この請求の最新内容と取消の影響/ }).check(); await page.getByRole('button', { name: '請求の取消を確定', exact: true }).click(); await expect(page.getByRole('status')).toContainText('請求を取り消しました'); await expect(list.getByRole('cell', { name: `${prefix}-ACTIVE`, exact: true })).toHaveCount(0); await expect(list.getByRole('row')).toHaveCount(2);
  const otherContext = await browser.newContext({ timezoneId: 'Pacific/Kiritimati' }); const other = await otherContext.newPage();
  try {
    await loginInvoiceUser(other, 'beta-admin@example.test', new URL('/', page.url()).toString()); expect((await other.request.get(`/api/invoices/${original.id}/history`)).status()).toBe(404); expect(await receivables(other, own.id, 'CANCELLED')).toHaveLength(0);
    await other.getByRole('button', { name: '債権一覧', exact: true }).click(); await other.getByRole('combobox', { name: '請求の状態', exact: true }).selectOption('CANCELLED'); await other.getByRole('button', { name: '条件で表示', exact: true }).click(); await expect(other.getByRole('cell', { name: original.invoiceNumber, exact: true })).toHaveCount(0);
  } finally { await otherContext.close(); }
  expect(await receivables(page, old.id)).toEqual(preserved);
  await page.context().clearCookies(); await page.getByRole('combobox', { name: '請求の状態', exact: true }).selectOption('CANCELLED'); await page.getByRole('button', { name: '条件で表示', exact: true }).click(); await expect(page.getByLabel('パスワード', { exact: true })).toHaveValue(''); await expect(history).toHaveCount(0); await expect(list).toHaveCount(0);
});
