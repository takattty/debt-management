import { expect, type Page } from '@playwright/test';
import { test } from './fixtures';
import { loginPaymentUser } from './payment-fixtures';
import { sessionHeaders } from './invoice-fixtures';
import type { Customer, ImportBatch, Payment, PaymentCandidates, Session, AllocationHistoryPage } from '../src/api';

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
  await expect(page.getByRole('checkbox', { checked: true })).toHaveCount(0); const one = first.items[0]; await page.getByRole('checkbox', { name: `請求 ${one.invoiceNumber} を選択`, exact: true }).check();
  const nextRequest = page.waitForRequest((request) => request.url().includes(`/api/payments/${payment.id}/candidates?`) && request.url().includes('cursor=')); await page.getByRole('button', { name: '次の候補50件', exact: true }).click(); expect(new URL((await nextRequest).url()).searchParams.get('cursor')).toBe(first.nextCursor);
  const next: PaymentCandidates = await (await page.request.get(`/api/payments/${payment.id}/candidates?limit=50&cursor=${encodeURIComponent(first.nextCursor!)}`)).json(); const two = next.items.find((item) => item.invoiceNumber.startsWith(prefix) && item.customerId !== one.customerId)!; expect(two).toBeDefined();
  await page.getByRole('checkbox', { name: `請求 ${two.invoiceNumber} を選択`, exact: true }).check(); const chosen = page.getByRole('region', { name: '選択した請求', exact: true }); await expect(chosen).toContainText(one.invoiceNumber); await expect(chosen).toContainText(two.invoiceNumber);
  const otherContext = await browser.newContext(); const other = await otherContext.newPage();
  try { await loginPaymentUser(other, 'beta-admin@example.test', new URL('/', page.url()).toString()); expect((await other.request.get(`/api/payments/${payment.id}/candidates`)).status()).toBe(404); }
  finally { await otherContext.close(); }
  await page.getByRole('button', { name: '最新候補を取得して選び直す', exact: true }).click(); await expect(chosen).toHaveCount(0);
  await page.context().clearCookies(); await page.getByRole('button', { name: '最新候補を取得して選び直す', exact: true }).click(); await expect(page.getByLabel('パスワード', { exact: true })).toHaveValue(''); await expect(page.getByRole('table', { name: '消込候補', exact: true })).toHaveCount(0);
});

test('real split allocation with explicit fees recovers a lost response using the same immutable operation', async ({ page }) => {
  await loginPaymentUser(page); const { payment, prefix } = await seed(page); await openPayment(page, payment);
  for (const index of [0, 1]) await page.getByRole('checkbox', { name: `請求 ${prefix}-INV-${index} を選択`, exact: true }).check();
  await page.getByRole('button', { name: '選択内容を再取得して確認', exact: true }).click();
  await page.getByLabel(`現金（円） ${prefix}-INV-0`, { exact: true }).fill('6000'); await page.getByLabel(`現金（円） ${prefix}-INV-1`, { exact: true }).fill('3000'); await page.getByLabel(`手数料（円） ${prefix}-INV-1`, { exact: true }).fill('500');
  await page.getByLabel('消込理由', { exact: true }).fill('架空明細の合算振込と手数料を確認'); await page.getByRole('checkbox', { name: '顧客・請求・現金額・明示した手数料を確認しました', exact: true }).check();
  const bodies: string[] = []; let drop = true;
  await page.route(`**/api/payments/${payment.id}/allocations`, async (route) => { if (route.request().method() !== 'POST') { await route.continue(); return; } bodies.push(route.request().postData()!); const response = await route.fetch(); expect(response.status()).toBe(200); if (drop) { drop = false; await route.abort('failed'); } else await route.fulfill({ response }); });
  await page.getByRole('button', { name: 'この内容で消込を確定', exact: true }).click(); await expect(page.getByRole('alert')).toContainText('確定結果が不明'); await expect(page.getByRole('button', { name: '消込を閉じる', exact: true })).toBeDisabled(); await expect(page.getByRole('button', { name: '顧客管理', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '同じ操作IDで結果を確認', exact: true }).click(); await expect(page.getByRole('status')).toContainText('消込を確定しました'); expect(bodies).toHaveLength(2); expect(bodies[0]).toBe(bodies[1]);
  const current: PaymentCandidates = await (await page.request.get(`/api/payments/${payment.id}/candidates`)).json(); expect(current.unallocatedAmount).toBe(1000); expect(current.expectedPaymentVersion).toBe('1');
  for (const index of [0, 1]) { const item = current.items.find((candidate) => candidate.invoiceNumber === `${prefix}-INV-${index}`)!; expect(item.outstandingAmount).toBe(index === 0 ? 4000 : 6500); }
  const session: Session = await (await page.request.get('/api/session')).json(); expect(session.email).toBe('alpha-accountant@example.test'); await page.reload(); await openPayment(page, payment); await expect(page.getByText(/現在の未充当現金：1,000円/)).toBeVisible(); await expect(page.getByText(/入金原本：.*10,000円/)).toBeVisible();
  const firstHistory: AllocationHistoryPage = await (await page.request.get(`/api/payments/${payment.id}/allocations`)).json(); const original = firstHistory.items.find((item) => item.kind === 'CONFIRM')!;
  const history = page.getByRole('region', { name: '消込履歴', exact: true }); await expect(history).toContainText('alpha-accountant@example.test'); await expect(history).toContainText('日本時間 / JST');
  await page.getByRole('button', { name: `操作 ${original.id} の取消内容を再取得`, exact: true }).click(); await page.getByLabel('取消理由', { exact: true }).fill('架空手数料を見直すため全件取消'); await page.getByRole('checkbox', { name: '全請求の現金と手数料をまとめて取り消すことを確認しました', exact: true }).check();
  const reversalBodies: string[] = []; let dropReversal = true;
  await page.route(`**/api/payments/${payment.id}/allocations/${original.id}/reversal`, async (route) => { reversalBodies.push(route.request().postData()!); const response = await route.fetch(); expect(response.status()).toBe(200); if (dropReversal) { dropReversal = false; await route.abort('failed'); } else await route.fulfill({ response }); });
  await page.getByRole('button', { name: '操作全体を取り消す', exact: true }).click(); await expect(history.getByRole('alert')).toContainText('取消結果が不明'); await expect(page.getByRole('button', { name: '消込を閉じる', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '同じ取消操作IDで結果を確認', exact: true }).click(); await expect(history.getByRole('status')).toContainText('操作全体を取り消しました'); expect(reversalBodies).toHaveLength(2); expect(reversalBodies[0]).toBe(reversalBodies[1]);
  await expect(page.getByRole('checkbox', { checked: true })).toHaveCount(0); await expect(page.getByText(/現在の未充当現金：10,000円/)).toBeVisible();
  const reversed: AllocationHistoryPage = await (await page.request.get(`/api/payments/${payment.id}/allocations`)).json(); expect(reversed.items).toHaveLength(2); expect(reversed.items.find((item) => item.id === original.id)?.reversedByOperationId).toBeTruthy();
  for (const index of [0, 1]) await page.getByRole('checkbox', { name: `請求 ${prefix}-INV-${index} を選択`, exact: true }).check();
  await page.getByRole('button', { name: '選択内容を再取得して確認', exact: true }).click(); await page.getByLabel(`現金（円） ${prefix}-INV-0`, { exact: true }).fill('6000'); await page.getByLabel(`現金（円） ${prefix}-INV-1`, { exact: true }).fill('4000'); await page.getByLabel(`手数料（円） ${prefix}-INV-1`, { exact: true }).fill('300');
  await page.getByLabel('消込理由', { exact: true }).fill('架空手数料300円で再確定'); await page.getByRole('checkbox', { name: '顧客・請求・現金額・明示した手数料を確認しました', exact: true }).check(); await page.getByRole('button', { name: 'この内容で消込を確定', exact: true }).click(); await expect(page.getByRole('region', { name: '消込内容の確認', exact: true }).getByRole('status')).toContainText('消込を確定しました');
  await expect(page.getByText('未充当現金がありません。過去の消込を変更する場合は元の操作全体を取り消してください。', { exact: true })).toBeVisible();
  const after: AllocationHistoryPage = await (await page.request.get(`/api/payments/${payment.id}/allocations`)).json(); expect(after.items).toHaveLength(3); expect(after.expectedPaymentVersion).toBe('3');
  expect(after.items.find((item) => item.id === original.id)?.lines).toEqual(original.lines.map((line) => ({ ...line, currentInvoiceVersion: '3' })));
  const latest = after.items.find((item) => item.kind === 'CONFIRM' && item.id !== original.id)!; expect(latest.lines.find((line) => line.invoiceNumber === `${prefix}-INV-1`)?.feeAmount).toBe(300);
  await page.reload(); await openPayment(page, payment, false); await expect(page.getByRole('region', { name: '消込履歴', exact: true })).toContainText(original.id); await expect(page.getByText(/現在の未充当現金：0円/)).toBeVisible();
});
