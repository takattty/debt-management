import { readFile } from 'node:fs/promises';
import { expect } from '@playwright/test';
import { test } from './fixtures';
import { loginPaymentUser, paymentHeader, previewPayments } from './payment-fixtures';
import type { components } from '../src/generated/schema';

test('real payment preview pages dedicated rows and physical correction details, downloads guidance and repairs without inserting payments', async ({ page, browser }) => {
  const prefix = `PREVIEW-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  await loginPaymentUser(page); await page.getByRole('button', { name: '入金CSV取込', exact: true }).click();
  const templateDownload = page.waitForEvent('download'); await page.getByRole('button', { name: 'テンプレートをダウンロード', exact: true }).click();
  const template = await templateDownload; expect(template.suggestedFilename()).toBe('payments-template.csv'); expect(await readFile((await template.path())!, 'utf8')).toBe('\uFEFFtransaction_id,transfer_date,payer_name,amount\r\n');
  const invalidCsv = '\uFEFF' + paymentHeader + `${prefix}-OK,2026-10-30,架空振込人,100000\n` + Array.from({ length: 51 }, (_, index) => `${prefix}-BAD-${index + 1},2026-10-30,架空振込人,0`).join('\n') + '\n';
  const invalid = await previewPayments(page, invalidCsv); expect(invalid.status).toBe('PREVIEW_INVALID');
  const rows = page.getByRole('table', { name: '入金CSVプレビュー', exact: true }); const errors = page.getByRole('table', { name: 'CSVエラー一覧', exact: true });
  await expect(rows.getByRole('row')).toHaveCount(51); await expect(rows).toContainText(`${prefix}-OK`); await expect(rows).toContainText('振込名義');
  await expect(errors).toContainText('amount'); await expect(errors.getByRole('row')).toHaveCount(51);
  await page.getByRole('button', { name: '次の50行', exact: true }).click(); await expect(rows).toContainText(`${prefix}-BAD-51`); await expect(rows.getByRole('row')).toHaveCount(3); await expect(rows).not.toContainText(`${prefix}-OK`);
  await page.getByRole('button', { name: '次のエラー50件', exact: true }).click(); await expect(errors.getByRole('row')).toHaveCount(2); await expect(errors).toContainText('53'); await expect(page.getByRole('button', { name: '次のエラー50件', exact: true })).toBeDisabled();
  const reportDownload = page.waitForEvent('download'); await page.getByRole('button', { name: '全エラーをCSVでダウンロード', exact: true }).click();
  const report = await reportDownload; const guidance = await readFile((await report.path())!, 'utf8'); expect(guidance).toContain('"53","amount","0"');
  const repaired = await previewPayments(page, '\uFEFF' + paymentHeader + `${prefix}-OK,0001-01-01, ｶｸｳ é é ,9007199254740991\n${prefix}-FIXED,9999-12-31,架空振込人,100000\n`);
  expect(repaired.id).not.toBe(invalid.id); expect(repaired.status).toBe('PREVIEW_VALID'); await expect(errors).toHaveCount(0);
  await expect(rows).toContainText('0001-01-01'); await expect(rows).toContainText('9999-12-31'); await expect(rows).toContainText('9007199254740991'); await expect(rows).toContainText('ｶｸｳ é é');
  await expect(page.getByText(/まだ1件も登録されていません/)).toBeVisible();
  const payments: components['schemas']['PaymentResponse'][] = (await (await page.request.get('/api/payments')).json()).items;
  expect(payments.some((entry) => entry.transactionId.startsWith(prefix))).toBe(false);
  const old = await (await page.request.get(`/api/imports/${invalid.id}/errors?cursor=50&limit=50`)).json(); expect(old.items[0].rowNumber).toBe(53);
  const otherContext = await browser.newContext(); const other = await otherContext.newPage();
  try {
    await loginPaymentUser(other, 'beta-admin@example.test', new URL('/', page.url()).toString());
    expect((await other.request.get(`/api/imports/${repaired.id}/payment-rows`)).status()).toBe(404); expect((await other.request.get(`/api/imports/${invalid.id}/errors.csv`)).status()).toBe(404);
  } finally { await otherContext.close(); }
  await page.getByRole('button', { name: '顧客管理', exact: true }).click(); await page.getByRole('button', { name: '入金CSV取込', exact: true }).click();
  await expect(rows).toHaveCount(0); await expect(page.getByLabel('入金CSVファイル', { exact: true })).toHaveValue('');
});
