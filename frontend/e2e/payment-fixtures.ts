import { expect, type Page } from '@playwright/test';
import type { components } from '../src/generated/schema';
import type { ImportBatch, Payment } from '../src/api';

export const paymentHeader = 'transaction_id,transfer_date,payer_name,amount\n';
export async function loginPaymentUser(page: Page, email = 'alpha-accountant@example.test', url = '/') {
  await page.goto(url); await page.getByLabel('メールアドレス').fill(email);
  await page.getByLabel('パスワード').fill(process.env.LOCAL_SEED_PASSWORD ?? 'debt_local_demo_2026');
  await page.getByRole('button', { name: 'ログイン', exact: true }).click();
  await expect(page.getByRole('heading', { name: '顧客を登録', exact: true })).toBeVisible();
}
export async function previewPayments(page: Page, content: string): Promise<ImportBatch> {
  await page.getByLabel('入金CSVファイル', { exact: true }).setInputFiles({ name: 'payments.csv', mimeType: 'text/csv', buffer: Buffer.from(content) });
  const response = page.waitForResponse((entry) => entry.url().endsWith('/api/imports/payments/preview') && entry.request().method() === 'POST');
  await page.getByRole('button', { name: 'CSVをプレビュー', exact: true }).click();
  const stored = await response; expect(stored.status()).toBe(201); return stored.json();
}

export async function findPayment(page: Page, transactionId: string, date: string): Promise<Payment> {
  let cursor: string | null = null;
  do {
    const response = await page.request.get(`/api/payments?dateFrom=${date}&dateTo=${date}&limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`); expect(response.status()).toBe(200);
    const current: components['schemas']['PaymentsPage'] = await response.json(); const found = current.items.find((item) => item.transactionId === transactionId);
    if (found) return found;
    cursor = current.nextCursor;
  } while (cursor);
  throw new Error(`Owned payment fixture not found: ${transactionId}`);
}
