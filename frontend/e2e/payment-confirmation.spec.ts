import { statusFeedback } from './status-feedback';
import { expect } from '@playwright/test';
import { test } from './fixtures';
import { loginPaymentUser, paymentHeader, previewPayments } from './payment-fixtures';
import type { Payment, PaymentsQuery } from '../src/api';
import type { components } from '../src/generated/schema';

async function filterDates(page: import('@playwright/test').Page, dateFrom: string, dateTo = dateFrom) {
  await page.getByLabel('振込日（開始）', { exact: true }).fill(dateFrom); await page.getByLabel('振込日（終了）', { exact: true }).fill(dateTo);
  await page.getByRole('button', { name: '条件で表示', exact: true }).click();
}

test('real payment confirmation recovers a lost response, persists boundary dates and integer amounts, opens the original duplicate and isolates tenants', async ({ page, browser }) => {
  const prefix = `PAY-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const early = `${prefix}-EARLY,0001-01-01, ｶｸｳ é é ,9007199254740991\n`; const late = `${prefix}-LATE,9999-12-31,架空振込人,100000\n`;
  await loginPaymentUser(page); await page.getByRole('button', { name: '入金CSV取込', exact: true }).click();
  const batch = await previewPayments(page, '\uFEFF' + paymentHeader + early + late); let confirmations = 0;
  await page.route(`**/api/imports/${batch.id}/confirm`, async (route) => { confirmations++; const real = await route.fetch(); expect(real.status()).toBe(200); await route.abort('failed'); });
  await page.getByRole('button', { name: '全2件を確定して登録', exact: true }).click(); await expect(statusFeedback(page, '2件の入金を登録しました')).toContainText('2件の入金を登録しました'); expect(confirmations).toBe(1);
  await page.getByRole('button', { name: '入金一覧へ', exact: true }).click(); await filterDates(page, '0001-01-01');
  const list = page.getByRole('table', { name: '入金一覧', exact: true }); await expect(list).toContainText(`${prefix}-EARLY`); await expect(list).toContainText('9,007,199,254,740,991'); await expect(list).toContainText('ｶｸｳ é é');
  const earlyStored: Payment[] = (await (await page.request.get('/api/payments?dateFrom=0001-01-01&dateTo=0001-01-01')).json()).items;
  const stored = earlyStored.find((item) => item.transactionId === `${prefix}-EARLY`)!; expect(stored.amount).toBe(9007199254740991); expect(stored.unallocatedAmount).toBe(stored.amount); expect(stored.transferDate).toBe('0001-01-01'); expect(stored.sourceBatchId).toBe(batch.id);
  await filterDates(page, '9999-12-31'); await expect(list).toContainText(`${prefix}-LATE`); await expect(list).toContainText('9999-12-31');
  await page.reload(); await page.getByRole('button', { name: '入金一覧', exact: true }).click(); await filterDates(page, '9999-12-31'); await expect(list).toContainText(`${prefix}-LATE`);
  await expect(page.getByText(/手数料は入金原本の金額を変更しません/)).toBeVisible();
  await page.getByRole('button', { name: '入金CSV取込', exact: true }).click(); const duplicate = await previewPayments(page, paymentHeader + late + early);
  expect(duplicate.originalBatchId).toBe(batch.id); await expect(page.getByRole('button', { name: /確定して登録/ })).toBeDisabled();
  await page.getByRole('button', { name: '元の取込結果を確認', exact: true }).click(); await expect(statusFeedback(page, '2件の入金を登録しました')).toContainText('2件の入金を登録しました'); await expect(page.getByRole('table', { name: '入金CSVプレビュー', exact: true })).toContainText(`${prefix}-EARLY`);
  const otherContext = await browser.newContext(); const other = await otherContext.newPage();
  try {
    await loginPaymentUser(other, 'beta-admin@example.test', new URL('/', page.url()).toString()); expect((await other.request.get(`/api/imports/${batch.id}/payment-rows`)).status()).toBe(404);
    await other.getByRole('button', { name: '入金CSV取込', exact: true }).click(); const otherBatch = await previewPayments(other, paymentHeader + early + late); expect(otherBatch.status).toBe('PREVIEW_VALID');
    await other.getByRole('button', { name: '全2件を確定して登録', exact: true }).click(); await expect(statusFeedback(other, '2件の入金を登録しました')).toContainText('2件の入金を登録しました'); await other.getByRole('button', { name: '入金一覧へ', exact: true }).click(); await filterDates(other, '0001-01-01');
    const otherPayments: Payment[] = (await (await other.request.get('/api/payments?dateFrom=0001-01-01&dateTo=0001-01-01')).json()).items;
    const otherStored = otherPayments.find((item) => item.transactionId === stored.transactionId)!; expect(otherStored.id).not.toBe(stored.id); expect(otherStored.sourceBatchId).toBe(otherBatch.id); expect((await page.request.get(`/api/imports/${otherBatch.id}`)).status()).toBe(404);
  } finally { await otherContext.close(); }
});

test('real payment list keeps the server keyset and applied date filters across pages', async ({ page }) => {
  const prefix = `PAGE-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  await loginPaymentUser(page);
  let date = '';
  const firstYear = 4000 + Date.now() % 4000;
  for (let offset = 0; offset < 4000; offset++) {
    const candidate = `${4000 + (firstYear - 4000 + offset) % 4000}-11-30`;
    const existing = await (await page.request.get(`/api/payments?dateFrom=${candidate}&dateTo=${candidate}&limit=1`)).json();
    if (existing.items.length === 0) { date = candidate; break; }
  }
  expect(date).not.toBe(''); await page.getByRole('button', { name: '入金CSV取込', exact: true }).click();
  await previewPayments(page, paymentHeader + Array.from({ length: 52 }, (_, index) => `${prefix}-${index + 1},${date},架空ページング振込人,100000`).join('\n') + '\n');
  await page.getByRole('button', { name: '全52件を確定して登録', exact: true }).click(); await expect(statusFeedback(page, '52件の入金を登録しました')).toContainText('52件の入金を登録しました'); await page.getByRole('button', { name: '入金一覧へ', exact: true }).click();
  const firstResponse = page.waitForResponse((response) => response.url().includes('/api/payments?') && response.url().includes('dateFrom=')); await filterDates(page, date);
  const first: components['schemas']['PaymentsPage'] = await (await firstResponse).json(); expect(first.items).toHaveLength(50); expect(first.nextCursor).not.toBeNull();
  const list = page.getByRole('table', { name: '入金一覧', exact: true }); await expect(list.getByRole('row')).toHaveCount(51);
  await page.getByLabel('振込日（終了）', { exact: true }).fill('');
  const nextRequest = page.waitForRequest((request) => request.url().includes('/api/payments?') && request.url().includes('cursor=')); await page.getByRole('button', { name: '次の50件', exact: true }).click();
  const parameters = new URL((await nextRequest).url()).searchParams; const query: PaymentsQuery = { dateFrom: parameters.get('dateFrom')!, dateTo: parameters.get('dateTo')!, cursor: parameters.get('cursor')!, limit: Number(parameters.get('limit')) };
  expect(query).toEqual({ dateFrom: date, dateTo: date, cursor: first.nextCursor, limit: 50 }); await expect(list.getByRole('row')).toHaveCount(3); await expect(page.getByRole('button', { name: '次の50件', exact: true })).toBeDisabled();
  for (const item of first.items) await expect(list.getByRole('cell', { name: item.transactionId, exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '最初のページを再取得', exact: true }).click(); await expect(list.getByRole('row')).toHaveCount(51);
});

test('preview expiry requires a new real snapshot and session loss clears the payment import without writing', async ({ page }) => {
  const prefix = `EXP-${Date.now()}-${Math.floor(Math.random() * 100000)}`; const csv = paymentHeader + `${prefix},2026-10-30,架空有効期限振込人,100000\n`;
  await loginPaymentUser(page); await page.getByRole('button', { name: '入金CSV取込', exact: true }).click(); const original = await previewPayments(page, csv);
  // The response carries the actual server expiry; only the browser clock advances.
  await page.clock.setFixedTime(new Date(Date.parse(original.expiresAt) + 1000));
  const downloaded = page.waitForEvent('download'); await page.getByRole('button', { name: 'テンプレートをダウンロード', exact: true }).click(); await downloaded;
  await expect(page.getByText('有効期限が切れています。CSVをもう一度プレビューしてください。', { exact: true })).toBeVisible(); await expect(page.getByRole('button', { name: '全1件を確定して登録', exact: true })).toBeDisabled();
  await page.clock.setFixedTime(new Date()); const fresh = await previewPayments(page, csv); expect(fresh.id).not.toBe(original.id); await expect(page.getByRole('button', { name: '全1件を確定して登録', exact: true })).toBeEnabled();
  await page.context().clearCookies(); await page.getByRole('button', { name: '全1件を確定して登録', exact: true }).click();
  await expect(page.getByLabel('パスワード', { exact: true })).toHaveValue(''); await expect(page.getByRole('table', { name: '入金CSVプレビュー', exact: true })).toHaveCount(0); await expect(page.getByRole('alert')).toContainText('ログイン');
  await page.clock.setFixedTime(new Date()); await loginPaymentUser(page);
  expect((await (await page.request.get(`/api/imports/${fresh.id}`)).json()).status).toBe('PREVIEW_VALID');
});
