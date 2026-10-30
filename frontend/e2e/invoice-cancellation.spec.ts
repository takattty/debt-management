import { expect } from '@playwright/test';
import { test } from './fixtures';
import { createInvoice, filterInvoicesForCustomer, sessionHeaders } from './invoice-fixtures';

test('real cancellation requires renewed confirmation after conflict, removes receivables, retains original history and reserves the invoice number', async ({ page }) => {
  const { invoice, customer, invoiceNumber } = await createInvoice(page);
  await page.getByLabel('取消理由', { exact: true }).fill('架空の二重請求を取消');
  await expect(page.getByRole('button', { name: '請求の取消を確定' })).toBeDisabled();
  await page.getByRole('checkbox', { name: /この請求の最新内容と取消の影響/ }).check();
  const rival = await page.request.put(`/api/invoices/${invoice.id}`, { headers: await sessionHeaders(page), data: { version: invoice.version, customerId: customer.id, issueDate: '2026-10-30', dueDate: '2026-12-15', amount: 120000, reason: '取消前に別の担当者が訂正' } }); expect(rival.status()).toBe(200);
  const rejected = page.waitForResponse((response) => response.url().endsWith(`/api/invoices/${invoice.id}/cancel`) && response.request().method() === 'POST');
  await page.getByRole('button', { name: '請求の取消を確定' }).click(); expect((await rejected).status()).toBe(409);
  await expect(page.getByRole('alert')).toContainText('他の担当者'); await expect(page.getByRole('button', { name: '請求の取消を確定' })).toBeDisabled();
  await page.getByRole('button', { name: '取消の最新内容・処理結果を取得' }).click();
  await expect(page.getByLabel('取消理由', { exact: true })).toHaveValue('架空の二重請求を取消');
  await expect(page.getByRole('checkbox', { name: /この請求の最新内容と取消の影響/ })).not.toBeChecked();
  await expect(page.getByRole('table', { name: '請求履歴' })).toContainText('取消前に別の担当者が訂正');
  await page.getByRole('checkbox', { name: /この請求の最新内容と取消の影響/ }).check();
  await page.getByRole('button', { name: '請求の取消を確定' }).click();
  await expect(page.getByRole('status')).toContainText('請求を取り消しました');
  await expect(page.getByLabel('現在の請求内容')).toContainText('取消済み');
  await expect(page.getByLabel('現在の請求内容')).toContainText('￥0');
  await expect(page.getByRole('button', { name: '訂正を保存' })).toBeDisabled(); await expect(page.getByRole('button', { name: '請求の取消を確定' })).toBeDisabled();
  await expect(page.getByRole('table', { name: '債権一覧' })).toHaveCount(0);
  const current = await (await page.request.get(`/api/invoices/${invoice.id}`)).json(); expect(current.status).toBe('CANCELLED'); expect(current.outstandingAmount).toBe(0); expect(current.amount).toBe(120000);
  expect((await (await page.request.get(`/api/receivables?customerId=${customer.id}`)).json()).items).toHaveLength(0);
  await page.reload(); await page.getByRole('button', { name: '債権一覧', exact: true }).click();
  await filterInvoicesForCustomer(page, customer.id);
  await expect(page.getByRole('table', { name: '債権一覧' })).toHaveCount(0);
  await page.getByLabel('請求IDで訂正・履歴を開く（取消済みも参照可）').fill(invoice.id); await page.getByRole('button', { name: '請求を開く', exact: true }).click();
  const history = page.getByRole('table', { name: '請求履歴' }); await expect(history).toContainText('登録時の原本'); await expect(history).toContainText('100,000'); await expect(history).toContainText('架空の二重請求を取消'); await expect(history).toContainText('取消済み');
  await page.getByRole('button', { name: '請求CSV取込', exact: true }).click();
  await page.getByLabel('請求CSVファイル').setInputFiles({ name: 'reserved.csv', mimeType: 'text/csv', buffer: Buffer.from(`customer_code,invoice_number,issue_date,due_date,amount\n${customer.code},${invoiceNumber},2026-10-30,2026-11-30,100000\n`) });
  await page.getByRole('button', { name: 'CSVをプレビュー', exact: true }).click();
  await expect(page.getByRole('table', { name: 'CSVエラー一覧' })).toContainText(invoiceNumber); await expect(page.getByRole('button', { name: /確定して登録/ })).toBeDisabled();
});

test('lost real cancellation response is recovered from PostgreSQL without a second cancellation', async ({ page }) => {
  const { invoice } = await createInvoice(page); let cancellations = 0;
  await page.route(`**/api/invoices/${invoice.id}/cancel`, async (route) => {
    cancellations++; const response = await route.fetch(); expect(response.status()).toBe(200); await route.abort('failed');
  });
  await page.getByLabel('取消理由', { exact: true }).fill('架空の通信障害時の取消'); await page.getByRole('checkbox', { name: /この請求の最新内容と取消の影響/ }).check();
  await page.getByRole('button', { name: '請求の取消を確定' }).click(); await expect(page.getByRole('alert')).toBeVisible(); await expect(page.getByRole('button', { name: '請求の取消を確定' })).toBeDisabled();
  await page.getByRole('button', { name: '取消の最新内容・処理結果を取得' }).click();
  await expect(page.getByRole('status')).toContainText('再送しません'); await expect(page.getByRole('table', { name: '請求履歴' })).toContainText('架空の通信障害時の取消');
  await expect(page.getByRole('button', { name: '請求の取消を確定' })).toBeDisabled(); expect(cancellations).toBe(1);
  await expect(page.getByRole('table', { name: '債権一覧' })).toHaveCount(0);
  const current = await (await page.request.get(`/api/invoices/${invoice.id}`)).json(); expect(current.status).toBe('CANCELLED'); expect(current.outstandingAmount).toBe(0); expect(current.version).toBe(invoice.version + 1);
});
