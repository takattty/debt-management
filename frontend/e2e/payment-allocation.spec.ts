import { expect, type Page } from '@playwright/test';
import { test } from './fixtures';
import { loginPaymentUser } from './payment-fixtures';
import { sessionHeaders } from './invoice-fixtures';
import type { Customer, ImportBatch, Payment, PaymentCandidates } from '../src/api';

async function importCsv(page: Page, kind: 'invoices' | 'payments', csv: string) {
  const headers = await sessionHeaders(page);
  const response = await page.request.post(`/api/imports/${kind}/preview`, { headers, multipart: { file: { name: `${kind}.csv`, mimeType: 'text/csv', buffer: Buffer.from(csv) } } });
  expect(response.status()).toBe(201); const batch: ImportBatch = await response.json(); expect(batch.status).toBe('PREVIEW_VALID');
  const confirm = await page.request.post(`/api/imports/${batch.id}/confirm`, { headers, data: { version: batch.version } }); expect(confirm.status()).toBe(200);
}
async function seed(page: Page, count = 2) {
  const prefix = `ALLOC-${Date.now()}-${Math.floor(Math.random() * 100000)}`; const payer = `架空振込人 ${prefix}`;
  const headers = await sessionHeaders(page); const customers: Customer[] = [];
  for (const suffix of ['A', 'B']) {
    const response = await page.request.post('/api/customers', { headers, data: { code: `${prefix}-${suffix}`, name: `架空消込顧客 ${suffix}` } }); expect(response.status()).toBe(201); customers.push(await response.json());
  }
  for (const customer of customers) { const alias = await page.request.post('/api/payer-aliases', { headers, data: { payerName: payer, customerId: customer.id } }); expect(alias.status()).toBe(201); }
  await importCsv(page, 'invoices', 'customer_code,invoice_number,issue_date,due_date,amount\n' + Array.from({ length: count }, (_, index) => `${customers[index % 2].code},${prefix}-INV-${index},2026-10-01,2026-11-01,10000`).join('\n') + '\n');
  await importCsv(page, 'payments', `transaction_id,transfer_date,payer_name,amount\n${prefix},2026-10-30,${payer},10000\n`);
  let cursor: string | undefined; let payment: Payment | undefined;
  do {
    const response = await page.request.get(`/api/payments?dateFrom=2026-10-30&dateTo=2026-10-30&limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`); expect(response.status()).toBe(200);
    const value: { items: Payment[]; nextCursor: string | null } = await response.json(); payment = value.items.find((item) => item.transactionId === prefix); cursor = value.nextCursor ?? undefined;
  } while (!payment && cursor);
  expect(payment).toBeDefined(); return { prefix, payment: payment!, customers };
}
async function openPayment(page: Page, payment: Payment, hasCandidates = true) {
  await page.getByRole('button', { name: '入金一覧', exact: true }).click();
  await page.getByLabel('振込日（開始）', { exact: true }).fill(payment.transferDate); await page.getByLabel('振込日（終了）', { exact: true }).fill(payment.transferDate);
  const filtered = page.waitForResponse((response) => { const url = new URL(response.url()); return url.pathname === '/api/payments' && url.searchParams.get('dateFrom') === payment.transferDate && url.searchParams.get('dateTo') === payment.transferDate && !url.searchParams.has('cursor'); });
  await page.getByRole('button', { name: '条件で表示', exact: true }).click(); const response = await filtered; expect(response.status()).toBe(200);
  let rendered: { items: Payment[]; nextCursor: string | null } = await response.json();
  const list = page.getByRole('table', { name: '入金一覧', exact: true });
  for (const item of rendered.items) await expect(list.getByRole('cell', { name: item.transactionId, exact: true })).toBeVisible();
  const button = page.getByRole('button', { name: `入金 ${payment.transactionId} の消込`, exact: true });
  while (!rendered.items.some((item) => item.id === payment.id)) {
    expect(rendered.nextCursor).not.toBeNull(); const cursor = rendered.nextCursor!;
    const next = page.waitForResponse((entry) => { const url = new URL(entry.url()); return url.pathname === '/api/payments' && url.searchParams.get('cursor') === cursor; });
    await page.getByRole('button', { name: '次の50件', exact: true }).click(); const response = await next; expect(response.status()).toBe(200); rendered = await response.json();
    for (const item of rendered.items) await expect(list.getByRole('cell', { name: item.transactionId, exact: true })).toBeVisible();
  }
  await expect(button).toBeVisible();
  await button.click(); if (hasCandidates) await expect(page.getByRole('table', { name: '消込候補', exact: true })).toBeVisible(); else await expect(page.getByText('未充当現金がありません。過去の消込を変更する場合は元の操作全体を取り消してください。', { exact: true })).toBeVisible();
}

test('real candidate selection is human, cross-customer, keyset-paged and tenant/session scoped', async ({ page, browser }) => {
  await loginPaymentUser(page); const { payment, prefix } = await seed(page, 52); await openPayment(page, payment);
  await expect(page.getByText('振込名義が2顧客に対応しています。対象を選んでください。', { exact: true })).toBeVisible();
  const firstResponse = await page.request.get(`/api/payments/${payment.id}/candidates?limit=50`); expect(firstResponse.status()).toBe(200); const first: PaymentCandidates = await firstResponse.json();
  expect(first.requiresHumanConfirmation).toBe(true); expect(first.expectedPaymentVersion).toMatch(/^(0|[1-9][0-9]*)$/); expect(first.items).toHaveLength(50); expect(first.items.every((item) => item.invoiceNumber.startsWith(prefix))).toBe(true);
  await expect(page.getByRole('checkbox', { checked: true })).toHaveCount(0);
  const nextResponse = await page.request.get(`/api/payments/${payment.id}/candidates?limit=50&cursor=${encodeURIComponent(first.nextCursor!)}`); expect(nextResponse.status()).toBe(200); const next: PaymentCandidates = await nextResponse.json();
  const two = next.items.find((item) => item.invoiceNumber.startsWith(prefix))!; expect(two).toBeDefined(); const one = first.items.find((item) => item.invoiceNumber.startsWith(prefix) && item.customerId !== two.customerId)!; expect(one).toBeDefined();
  await page.getByRole('checkbox', { name: `請求 ${one.invoiceNumber} を選択`, exact: true }).check();
  const nextRequest = page.waitForRequest((request) => request.url().includes(`/api/payments/${payment.id}/candidates?`) && request.url().includes('cursor=')); await page.getByRole('button', { name: '次の候補50件', exact: true }).click(); expect(new URL((await nextRequest).url()).searchParams.get('cursor')).toBe(first.nextCursor);
  await page.getByRole('checkbox', { name: `請求 ${two.invoiceNumber} を選択`, exact: true }).check(); const chosen = page.getByRole('region', { name: '選択した請求', exact: true }); await expect(chosen).toContainText(one.invoiceNumber); await expect(chosen).toContainText(two.invoiceNumber);
  const otherContext = await browser.newContext(); const other = await otherContext.newPage();
  try { await loginPaymentUser(other, 'beta-admin@example.test', new URL('/', page.url()).toString()); expect((await other.request.get(`/api/payments/${payment.id}/candidates`)).status()).toBe(404); }
  finally { await otherContext.close(); }
  await page.getByRole('button', { name: '最新候補を取得して選び直す', exact: true }).click(); await expect(chosen).toHaveCount(0);
  await expect(page.getByRole('button', { name: '最新候補を取得して選び直す', exact: true })).toBeEnabled();
  await page.context().clearCookies();
  const rejected = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/payments/${payment.id}/candidates` && response.status() === 401);
  await page.getByRole('button', { name: '最新候補を取得して選び直す', exact: true }).click(); expect((await rejected).status()).toBe(401);
  await expect(page.getByLabel('パスワード', { exact: true })).toHaveValue(''); await expect(page.getByRole('table', { name: '消込候補', exact: true })).toHaveCount(0);
});
