import { expect, type Page } from '@playwright/test';
import { loginInvoiceUser, sessionHeaders } from './invoice-fixtures';
import type { CollectionCase, Customer, ImportBatch, Receivable } from '../src/api';

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
  await page.getByLabel('請求の状態', { exact: true }).selectOption(status); await page.getByLabel('顧客で絞り込む', { exact: true }).selectOption(customer.id); const loaded = page.waitForResponse((response) => response.url().includes('/api/receivables?') && new URL(response.url()).searchParams.get('customerId') === customer.id); await page.getByRole('button', { name: '条件で表示', exact: true }).click(); await loaded;
  const table = page.getByRole('table', { name: '債権一覧', exact: true }); await expect(table).toBeVisible();
  // A caller can have >50 own fixtures as well as unrelated older fixtures. Follow the UI cursor.
  while (await table.getByRole('cell', { name: invoice.invoiceNumber, exact: true }).count() === 0 && await page.getByRole('button', { name: '次の50件', exact: true }).isEnabled()) { await page.getByRole('button', { name: '次の50件', exact: true }).click(); await expect(page.getByRole('status')).toHaveCount(0); }
  await table.getByRole('button', { name: `請求 ${invoice.invoiceNumber} の対応・履歴`, exact: true }).click();
  const detail = page.getByRole('region', { name: '請求の対応・履歴', exact: true }); await expect(detail.getByRole('heading', { name: `${invoice.invoiceNumber} — ${customer.name}`, exact: true })).toBeVisible(); return detail;
}
export async function currentCollection(page: Page, invoiceId: string): Promise<CollectionCase> { const response = await page.request.get(`/api/collections/${invoiceId}?asOf=2026-10-30`); expect(response.status()).toBe(200); return response.json(); }
