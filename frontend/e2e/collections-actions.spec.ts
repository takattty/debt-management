import { expect, type Page } from '@playwright/test';
import { test } from './fixtures';
import type { AllocationConfirmation, CollectionHistory, ImportBatch, PaymentCandidate, PaymentCandidates } from '../src/api';
import { findPayment } from './payment-fixtures';
import { collectionFixtures, currentCollection, openCollectionFromInvoice } from './collection-fixtures';


async function allocateCurrent(page: Page, paymentId: string, invoiceId: string, cashAmount: number, headers: Record<string, string>) {
  let cursor: string | undefined; let first: PaymentCandidates | undefined; let selected: PaymentCandidate | undefined;
  do {
    const response = await page.request.get(`/api/payments/${paymentId}/candidates?limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`); expect(response.status()).toBe(200);
    const current: PaymentCandidates = await response.json(); first ??= current; expect(current.expectedPaymentVersion).toBe(first.expectedPaymentVersion); selected = current.items.find((item) => item.invoiceId === invoiceId); cursor = current.nextCursor ?? undefined;
  } while (!selected && cursor);
  expect(selected).toBeDefined(); const body: AllocationConfirmation = { requestId: crypto.randomUUID(), expectedPaymentVersion: first!.expectedPaymentVersion, reason: '架空の残高変化を確認', lines: [{ invoiceId, expectedInvoiceVersion: selected!.expectedInvoiceVersion, cashAmount, feeAmount: 0 }] };
  expect((await page.request.post(`/api/payments/${paymentId}/allocations`, { headers, data: body })).status()).toBe(200);
}

test('shared note plans, holds and release preserve drafts through conflict and recover a lost committed response without resending', async ({ page }) => {
  const fixture = await collectionFixtures(page); const invoice = fixture.invoices[0]; const detail = await openCollectionFromInvoice(page, fixture.customer, invoice);
  const originalTitle = page.getByRole('heading', { name: `請求の訂正・履歴：${invoice.invoiceNumber}`, exact: true });
  await page.getByRole('table', { name: '債権一覧', exact: true }).getByRole('button', { name: `請求 ${invoice.invoiceNumber} の訂正・履歴`, exact: true }).click(); await expect(originalTitle).toBeVisible(); await expect(detail).toHaveCount(0);
  await page.getByRole('table', { name: '債権一覧', exact: true }).getByRole('button', { name: `請求 ${invoice.invoiceNumber} の対応・履歴`, exact: true }).click(); await expect(detail.getByLabel('対応メモ（共有）')).toBeVisible(); await expect(originalTitle).toHaveCount(0);
  await detail.getByLabel('対応メモ（共有）').fill('電話で確認した架空の記録'); await detail.getByLabel('次回対応日（空欄で予定を解除）').fill('2026-11-03');
  const saved = page.waitForResponse((response) => response.url().endsWith(`/api/collections/${invoice.id}/actions`) && response.request().method() === 'POST'); await detail.getByRole('button', { name: '対応メモを記録', exact: true }).click(); expect((await saved).request().postDataJSON()).toEqual({ version: 0, note: '電話で確認した架空の記録', nextActionDate: '2026-11-03' }); await expect(detail).toContainText('対応を記録しました');
  await detail.getByLabel('保留・解除の理由').fill('\u00a0架空の顧客から確認を待つ\u00a0'); await detail.getByRole('button', { name: '理由を記録して保留', exact: true }).click(); await expect(detail.getByRole('button', { name: '理由を記録して保留を解除' })).toBeVisible();
  await detail.getByLabel('対応メモ（共有）').fill('保留中の事後メモ'); await detail.getByLabel('次回対応日（空欄で予定を解除）').clear(); await detail.getByRole('button', { name: '対応メモを記録', exact: true }).click(); await expect(detail.getByLabel('対応メモ（共有）')).toHaveValue(''); expect(await currentCollection(page, invoice.id)).toMatchObject({ version: 3, held: true, holdReason: '\u00a0架空の顧客から確認を待つ\u00a0', nextActionDate: null });
  const immutable = (await (await page.request.get(`/api/collections/${invoice.id}/history`)).json()).items;
  expect((await page.request.post(`/api/collections/${invoice.id}/actions`, { headers: fixture.headers, data: { version: 3, note: '別のタブからの更新', nextActionDate: '2026-11-09' } })).status()).toBe(200);
  await detail.getByLabel('保留・解除の理由').fill('確認したので解除する理由draft'); await detail.getByRole('button', { name: '理由を記録して保留を解除' }).click(); await expect(detail.getByRole('button', { name: '理由を記録して保留を解除' })).toBeDisabled(); await expect(detail.getByLabel('保留・解除の理由')).toHaveValue('確認したので解除する理由draft');
  await detail.getByRole('button', { name: 'GETで結果・最新状況を確認' }).click(); await expect(detail.getByRole('button', { name: '最新の版で再確認に戻る' })).toBeDisabled(); await expect(detail).toContainText('別のタブからの更新'); await detail.getByRole('checkbox').check(); await detail.getByRole('button', { name: '最新の版で再確認に戻る' }).click(); expect((await currentCollection(page, invoice.id)).version).toBe(4);
  await detail.getByRole('button', { name: '理由を記録して保留を解除' }).click(); await expect(detail.getByRole('button', { name: '理由を記録して保留', exact: true })).toBeVisible();
  let posts = 0; await page.route(`**/api/collections/${invoice.id}/actions`, async (route) => { if (route.request().method() !== 'POST') return route.continue(); posts++; const response = await route.fetch(); expect(response.status()).toBe(200); await route.abort('failed'); });
  await detail.getByLabel('対応メモ（共有）').fill('  \u00a0応答を失った架空の対応\u00a0  '); await detail.getByRole('button', { name: '対応メモを記録', exact: true }).click(); await expect(detail.getByRole('button', { name: '対応メモを記録', exact: true })).toBeDisabled(); await detail.getByRole('button', { name: 'GETで結果・最新状況を確認' }).click(); await expect(detail).toContainText('送信内容と一致する記録を履歴で確認しました'); expect(posts).toBe(1);
  const after = (await (await page.request.get(`/api/collections/${invoice.id}/history`)).json()).items; expect(after.slice(0, immutable.length)).toEqual(immutable); expect(after).toHaveLength(6); expect(after.every((event: CollectionHistory) => event.actorLabel === 'alpha-accountant@example.test')).toBe(true); expect(after.at(-1).nextActionDate).toBe('2026-11-09');
  await page.unroute(`**/api/collections/${invoice.id}/actions`); await detail.getByLabel('対応メモ（共有）').fill('離脱で消えるdraft'); await detail.getByLabel('保留・解除の理由').fill('離脱で消える理由'); await page.getByRole('button', { name: '顧客管理', exact: true }).click(); const reopened = await openCollectionFromInvoice(page, fixture.customer, invoice); await expect(reopened.getByLabel('対応メモ（共有）')).toHaveValue(''); await expect(reopened.getByLabel('保留・解除の理由')).toHaveValue(''); await expect(reopened.getByLabel('次回対応日（空欄で予定を解除）')).toHaveValue('2026-11-09');
  await reopened.getByLabel('対応メモ（共有）').fill('期限切れで保存しない'); await page.context().clearCookies(); await reopened.getByRole('button', { name: '対応メモを記録', exact: true }).click(); await expect(page.getByLabel('パスワード', { exact: true })).toHaveValue(''); await expect(reopened).toHaveCount(0);
});

test('cancelled originals accept post-event notes and paginate immutable history; real CSRF rejection clears every draft', async ({ page }) => {
  const fixture = await collectionFixtures(page); const invoice = fixture.invoices[0]; expect((await page.request.post(`/api/invoices/${invoice.id}/cancel`, { headers: fixture.headers, data: { version: 0, reason: '架空の取消後対応' } })).status()).toBe(200);
  for (let version = 0; version < 51; version++) expect((await page.request.post(`/api/collections/${invoice.id}/actions`, { headers: fixture.headers, data: { version, note: `原本の架空メモ${version + 1}`, nextActionDate: null } })).status()).toBe(200);
  const detail = await openCollectionFromInvoice(page, fixture.customer, invoice, 'CANCELLED'); await expect(detail).toContainText('取消済み・対象外'); await expect(detail.getByText('第51版：対応メモ', { exact: true })).toHaveCount(0); await detail.getByRole('button', { name: '次の50件の対応履歴' }).click(); await expect(detail.getByText('第51版：対応メモ', { exact: true })).toBeVisible();
  await detail.getByLabel('対応メモ（共有）').fill('取消後の事後確認'); await detail.getByRole('button', { name: '対応メモを記録', exact: true }).click(); await expect(detail.getByLabel('対応メモ（共有）')).toHaveValue(''); expect(await currentCollection(page, invoice.id)).toMatchObject({ version: 52, eligible: false, outstandingAmount: 0, latestNote: '取消後の事後確認' });
  await detail.getByRole('button', { name: '現在の残高で文面を作成' }).click(); await expect(detail.getByRole('alert')).toContainText('対象外'); await expect(detail.getByRole('button', { name: '表示した文面をコピー' })).toHaveCount(0);
  await page.route(`**/api/collections/${invoice.id}/actions`, async (route) => { if (route.request().method() !== 'POST') return route.continue(); const headers = { ...route.request().headers() }; for (const key of Object.keys(headers)) if (key.toLowerCase() === 'x-csrf-token') delete headers[key]; await route.continue({ headers }); });
  await detail.getByLabel('対応メモ（共有）').fill('CSRFで登録しない'); await detail.getByRole('button', { name: '対応メモを記録', exact: true }).click(); await expect(page.getByLabel('パスワード', { exact: true })).toHaveValue(''); await expect(detail).toHaveCount(0);
});

test('draft preview tracks real cash balances before copy, retains paid history and never sends an external message', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']); const fixture = await collectionFixtures(page); const invoice = fixture.invoices[0]; let detail = await openCollectionFromInvoice(page, fixture.customer, invoice);
  expect((await page.request.post(`/api/collections/${invoice.id}/actions`, { headers: fixture.headers, data: { version: 0, note: '完済前の対応原本', nextActionDate: null } })).status()).toBe(200);
  const originalHistory = (await (await page.request.get(`/api/collections/${invoice.id}/history`)).json()).items;
  await detail.getByRole('button', { name: '現在の残高で文面を作成' }).click(); await expect(detail).toContainText(`${fixture.customer.name} 御中`); await expect(detail).toContainText(invoice.invoiceNumber); await expect(detail).toContainText('現在の未回収額：100,000 円');
  const transaction = `${fixture.prefix}-CASH`; const csv = `transaction_id,transfer_date,payer_name,amount\n${transaction},2026-10-30,架空対応入金,100000\n`;
  const preview = await page.request.post('/api/imports/payments/preview', { headers: fixture.headers, multipart: { file: { name: 'collection-cash.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) } } }); expect(preview.status()).toBe(201); const batch: ImportBatch = await preview.json();
  expect((await page.request.post(`/api/imports/${batch.id}/confirm`, { headers: fixture.headers, data: { version: batch.version } })).status()).toBe(200); const payment = await findPayment(page, transaction, '2026-10-30');
  await allocateCurrent(page, payment.id, invoice.id, 30000, fixture.headers);
  await page.evaluate(() => navigator.clipboard.writeText('既存のコピー内容'));
  const writes: string[] = []; page.on('request', (request) => { if (request.method() !== 'GET') writes.push(new URL(request.url()).pathname); });
  await detail.getByRole('button', { name: '表示した文面をコピー' }).click(); await expect(detail).toContainText('残高または文面が変わりました'); await expect(detail).toContainText('現在の未回収額：70,000 円'); expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('既存のコピー内容');
  await detail.getByRole('button', { name: '表示した文面をコピー' }).click(); await expect(detail).toContainText('表示した件名と文面をコピーしました'); const clipboard = await page.evaluate(() => navigator.clipboard.readText()); expect(clipboard).toContain(invoice.invoiceNumber); expect(clipboard).toContain('70,000 円'); expect(writes).toEqual([]);
  await allocateCurrent(page, payment.id, invoice.id, 70000, fixture.headers); await page.reload(); detail = await openCollectionFromInvoice(page, fixture.customer, invoice);
  await expect(detail).toContainText('期日超過の未回収なし・対象外'); await expect(detail).toContainText('現在の未回収額：￥0'); await expect(detail).toContainText('完済前の対応原本'); expect((await (await page.request.get(`/api/collections/${invoice.id}/history`)).json()).items).toEqual(originalHistory);
  await detail.getByLabel('対応メモ（共有）').fill('完済後の事後確認'); await detail.getByRole('button', { name: '対応メモを記録', exact: true }).click(); await expect(detail.getByLabel('対応メモ（共有）')).toHaveValue(''); expect(await currentCollection(page, invoice.id)).toMatchObject({ outstandingAmount: 0, eligible: false, latestNote: '完済後の事後確認' });
  const history = (await (await page.request.get(`/api/collections/${invoice.id}/history`)).json()).items; expect(history.slice(0, originalHistory.length)).toEqual(originalHistory); expect(history).toHaveLength(2);
  await detail.getByRole('button', { name: '現在の残高で文面を作成' }).click(); await expect(detail.getByRole('alert')).toContainText('対象外'); await expect(detail.getByRole('button', { name: '表示した文面をコピー' })).toHaveCount(0);
});
