import { expect, type Page } from '@playwright/test';
import { loginInvoiceUser, sessionHeaders } from './invoice-fixtures';
import type { CollectionCase, Customer, ImportBatch, Receivable } from '../src/api';
import type { components } from '../src/generated/schema';

export async function collectionFixtures(page: Page, count = 1) {
  await loginInvoiceUser(page);
  const headers = await sessionHeaders(page); const prefix = `COL-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const customerResponse = await page.request.post('/api/customers', { headers, data: { code: prefix, name: `架空の対応先 ${prefix}` } }); expect(customerResponse.status()).toBe(201);
  const customer: Customer = await customerResponse.json();
  async function importCsv(kind: 'invoices' | 'payments', content: string) {
    const response = await page.request.post(`/api/imports/${kind}/preview`, { headers, multipart: { file: { name: 'collection-fixture.csv', mimeType: 'text/csv', buffer: Buffer.from(content) } } });
    expect(response.status()).toBe(201); const batch: ImportBatch = await response.json(); expect(batch.status).toBe('PREVIEW_VALID');
    expect((await page.request.post(`/api/imports/${batch.id}/confirm`, { headers, data: { version: batch.version } })).status()).toBe(200);
  }
  await importCsv('invoices', 'customer_code,invoice_number,issue_date,due_date,amount\n' + Array.from({ length: count }, (_, index) => `${prefix},${prefix}-${index},2026-10-01,2026-10-29,100000\n`).join(''));
  const invoices: Receivable[] = (await (await page.request.get(`/api/receivables?customerId=${customer.id}&limit=100`)).json()).items;
  expect(invoices).toHaveLength(count);
  await page.reload(); await expect(page.getByRole('heading', { name: '顧客を登録' })).toBeVisible();
  return { customer, invoices, prefix, headers };
}

export async function openCollectionFromInvoice(page: Page, customer: Customer, invoice: Receivable, status = 'ALL') {
  await page.getByRole('button', { name: '債権一覧', exact: true }).click();
  await page.getByLabel('請求の状態', { exact: true }).selectOption(status); await page.getByLabel('顧客で絞り込む', { exact: true }).selectOption(customer.id);
  const matches = (url: URL, cursor?: string) => url.pathname === '/api/receivables' && url.searchParams.get('customerId') === customer.id && url.searchParams.get('status') === status && (cursor ? url.searchParams.get('cursor') === cursor : !url.searchParams.has('cursor'));
  const loaded = page.waitForResponse((response) => matches(new URL(response.url()))); await page.getByRole('button', { name: '条件で表示', exact: true }).click();
  let response = await loaded; expect(response.status()).toBe(200); let current: components['schemas']['ReceivablesPage'] = await response.json();
  const table = page.getByRole('table', { name: '債権一覧', exact: true });
  async function rendered() { await expect(table.getByRole('row')).toHaveCount(current.items.length + 1); for (const item of current.items) await expect(table.getByRole('cell', { name: item.invoiceNumber, exact: true })).toBeVisible(); }
  await rendered();
  while (!current.items.some((item) => item.id === invoice.id)) {
    expect(current.nextCursor).not.toBeNull(); const cursor = current.nextCursor!; const next = page.waitForResponse((entry) => matches(new URL(entry.url()), cursor));
    await page.getByRole('button', { name: '次の50件', exact: true }).click(); response = await next; expect(response.status()).toBe(200); current = await response.json(); await rendered();
  }
  await table.getByRole('button', { name: `請求 ${invoice.invoiceNumber} の対応・履歴`, exact: true }).click();
  const detail = page.getByRole('region', { name: '請求の対応・履歴', exact: true }); await expect(detail.getByRole('heading', { name: `${invoice.invoiceNumber} — ${customer.name}`, exact: true })).toBeVisible(); return detail;
}
export async function currentCollection(page: Page, invoiceId: string): Promise<CollectionCase> { const response = await page.request.get(`/api/collections/${invoiceId}?asOf=2026-10-30`); expect(response.status()).toBe(200); return response.json(); }
