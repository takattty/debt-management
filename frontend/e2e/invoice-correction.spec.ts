import { statusFeedback } from './status-feedback';
import { expect } from '@playwright/test';
import { test } from './fixtures';
import { createInvoice, loginInvoiceUser, sessionHeaders } from './invoice-fixtures';

test('real invoice correction retains a conflicting draft, compares current facts and persists immutable history across reload and tenants', async ({ page, browser }) => {
  const { invoice, customer, invoiceNumber } = await createInvoice(page);
  await page.getByLabel('訂正後の請求額（円）').fill('110000'); await page.getByLabel('訂正理由', { exact: true }).fill('架空の契約金額を訂正');
  const otherContext = await browser.newContext(); const other = await otherContext.newPage();
  try {
    await loginInvoiceUser(other, 'alpha-admin@example.test', new URL('/', page.url()).toString());
    const rival = await other.request.put(`/api/invoices/${invoice.id}`, { headers: await sessionHeaders(other), data: { version: invoice.version, customerId: customer.id, issueDate: '2026-10-30', dueDate: '2026-12-15', amount: 120000, reason: '別の担当者が先に訂正' } }); expect(rival.status()).toBe(200);
    const rejected = page.waitForResponse((response) => response.request().method() === 'PUT' && response.url().endsWith(`/api/invoices/${invoice.id}`));
    await page.getByRole('button', { name: '訂正を保存' }).click(); expect((await rejected).status()).toBe(409);
    await expect(page.getByRole('alert')).toContainText('他の担当者'); await expect(page.getByRole('button', { name: '訂正を保存' })).toBeDisabled();
    await page.getByRole('button', { name: '最新内容・処理結果を取得', exact: true }).click();
    await expect(page.getByRole('checkbox', { name: /最新の登録内容と履歴/ })).toBeVisible();
    await expect(page.getByLabel('訂正後の請求額（円）')).toHaveValue('110000'); await expect(page.getByLabel('訂正理由', { exact: true })).toHaveValue('架空の契約金額を訂正');
    await expect(page.getByRole('table', { name: '請求履歴' })).toContainText('別の担当者が先に訂正');
    await page.getByRole('checkbox', { name: /最新の登録内容と履歴/ }).check(); await page.getByRole('button', { name: '訂正を保存' }).click();
    await expect(statusFeedback(page, '請求を訂正しました')).toBeVisible();
    const stored = await (await page.request.get(`/api/invoices/${invoice.id}`)).json(); expect(stored.amount).toBe(110000); expect(stored.version).toBe(invoice.version + 2); expect(stored.invoiceNumber).toBe(invoiceNumber);
    await page.reload(); await page.getByRole('button', { name: '債権一覧', exact: true }).click();
    await page.getByLabel('請求IDで訂正・履歴を開く（取消済みも参照可）').fill(invoice.id); await page.getByRole('button', { name: '請求を開く', exact: true }).click();
    const history = page.getByRole('table', { name: '請求履歴' }); await expect(history).toContainText('登録時の原本'); await expect(history).toContainText('100,000'); await expect(history).toContainText('架空の契約金額を訂正');
    await other.getByRole('button', { name: 'ログアウト', exact: true }).click(); await loginInvoiceUser(other, 'beta-admin@example.test', new URL('/', page.url()).toString());
    expect((await other.request.get(`/api/invoices/${invoice.id}`)).status()).toBe(404); expect((await other.request.get(`/api/invoices/${invoice.id}/history`)).status()).toBe(404);
  } finally { await otherContext.close(); }
});

test('lost real correction response blocks a second write and recovers the committed result', async ({ page }) => {
  const { invoice } = await createInvoice(page); let writes = 0;
  await page.route(`**/api/invoices/${invoice.id}`, async (route) => {
    if (route.request().method() !== 'PUT') { await route.continue(); return; }
    writes++; const real = await route.fetch(); expect(real.status()).toBe(200); await route.abort('failed');
  });
  await page.getByLabel('訂正後の請求額（円）').fill('130000'); await page.getByLabel('訂正理由', { exact: true }).fill('架空の通信障害の検証');
  await page.getByRole('button', { name: '訂正を保存' }).click(); await expect(page.getByRole('alert')).toBeVisible(); await expect(page.getByRole('button', { name: '訂正を保存' })).toBeDisabled();
  await page.getByRole('button', { name: '最新内容・処理結果を取得', exact: true }).click();
  await expect(page.getByRole('checkbox', { name: /最新の登録内容と履歴/ })).toBeVisible(); await expect(page.getByRole('table', { name: '請求履歴' })).toContainText('架空の通信障害の検証');
  await expect(page.getByRole('button', { name: '訂正を保存' })).toBeDisabled(); expect(writes).toBe(1);
  const persisted = await (await page.request.get(`/api/invoices/${invoice.id}`)).json(); expect(persisted.amount).toBe(130000); expect(persisted.version).toBe(invoice.version + 1);
});
