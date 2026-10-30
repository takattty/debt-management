import { expect, type Page } from '@playwright/test';
import type { Customer, Invoice, Session } from '../src/api';

export async function loginInvoiceUser(page: Page, email = 'alpha-accountant@example.test', url = '/') {
  await page.goto(url); await page.getByLabel('メールアドレス').fill(email);
  await page.getByLabel('パスワード').fill(process.env.LOCAL_SEED_PASSWORD ?? 'debt_local_demo_2026');
  await page.getByRole('button', { name: 'ログイン', exact: true }).click();
  await expect(page.getByRole('heading', { name: '顧客を登録' })).toBeVisible();
}
export async function createInvoice(page: Page) {
  const code = `MUT-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  await loginInvoiceUser(page);
  await page.getByLabel('顧客コード', { exact: false }).fill(code); await page.getByLabel('顧客名', { exact: false }).fill(`架空商事 ${code}`);
  await page.getByRole('button', { name: '顧客を登録', exact: true }).click(); await expect(page.getByRole('cell', { name: code, exact: true })).toBeVisible();
  const customer = ((await (await page.request.get('/api/customers')).json()) as Customer[]).find((entry) => entry.code === code)!;
  const invoiceNumber = `INV-${code}`;
  await page.getByRole('button', { name: '請求CSV取込', exact: true }).click();
  await page.getByLabel('請求CSVファイル').setInputFiles({ name: 'invoice.csv', mimeType: 'text/csv', buffer: Buffer.from(`customer_code,invoice_number,issue_date,due_date,amount\n${code},${invoiceNumber},2026-10-30,2026-11-30,100000\n`) });
  await page.getByRole('button', { name: 'CSVをプレビュー', exact: true }).click();
  await page.getByRole('button', { name: '全1件を確定して登録' }).click(); await expect(page.getByRole('status')).toContainText('1件の請求を登録しました');
  await page.getByRole('button', { name: '債権一覧へ' }).click();
  await page.getByRole('combobox', { name: '顧客で絞り込む' }).selectOption(customer.id); await page.getByRole('button', { name: '条件で表示' }).click();
  await expect(page.getByRole('table', { name: '債権一覧' })).toContainText(invoiceNumber);
  const invoice: Invoice = (await (await page.request.get(`/api/receivables?customerId=${customer.id}`)).json()).items[0];
  await page.getByRole('button', { name: `請求 ${invoiceNumber} の訂正・履歴`, exact: true }).click();
  await expect(page.getByRole('heading', { name: `請求の訂正・履歴：${invoiceNumber}` })).toBeVisible();
  return { invoice, customer, invoiceNumber };
}
export async function sessionHeaders(page: Page) {
  const session: Session = await (await page.request.get('/api/session')).json();
  return { [session.csrfHeaderName]: session.csrfToken, 'X-Requested-With': 'XMLHttpRequest' };
}
