import { expect, type Page } from '@playwright/test';
import { test } from './fixtures';
import { collectionFixtures, openCollectionFromInvoice } from './collection-fixtures';
import { loginInvoiceUser } from './invoice-fixtures';


// Locate only this test's fixture through the actual bounded UI cursors. Existing rows and global order are never assumed.
async function nextCandidates(page: Page) {
  const response = page.waitForResponse((entry) => entry.url().includes('/api/collections?') && new URL(entry.url()).searchParams.has('cursor'));
  await page.getByRole('button', { name: '次の50件', exact: true }).click(); await response;
  await expect(page.getByRole('table', { name: '督促候補一覧' })).toBeVisible();
}
async function findCandidate(page: Page, number: string) {
  const table = page.getByRole('table', { name: '督促候補一覧' });
  while (await table.getByRole('cell', { name: number, exact: true }).count() === 0) {
    await expect(page.getByRole('button', { name: '次の50件', exact: true })).toBeEnabled(); await nextCandidates(page);
  }
  return table.getByRole('row').filter({ has: page.getByRole('cell', { name: number, exact: true }) });
}

test('bounded candidate pages honor explicit day/held scope and original histories remain reachable after cancellation', async ({ page, browser }) => {
  test.setTimeout(90000);
  const fixture = await collectionFixtures(page, 53); const invoice = fixture.invoices[52];
  const note = await page.request.post(`/api/collections/${invoice.id}/actions`, { headers: fixture.headers, data: { version: 0, note: '残高変化前の原本メモ', nextActionDate: '2026-11-01' } }); expect(note.status()).toBe(200);
  const history = await (await page.request.get(`/api/collections/${invoice.id}/history`)).json();
  let detail = await openCollectionFromInvoice(page, fixture.customer, invoice); await expect(detail).toContainText('現在の未回収額：￥100,000'); await expect(detail).toContainText('残高変化前の原本メモ'); await expect(detail).toContainText('日本時間 / JST');
  const cancelled = fixture.invoices[51]; expect((await page.request.post(`/api/invoices/${cancelled.id}/cancel`, { headers: fixture.headers, data: { version: 0, reason: '対応原本の再参照確認' } })).status()).toBe(200);
  await page.getByRole('button', { name: '顧客管理', exact: true }).click(); detail = await openCollectionFromInvoice(page, fixture.customer, cancelled, 'CANCELLED'); await expect(detail).toContainText('取消済み・対象外'); await expect(detail).toContainText('現在の未回収額：￥0'); await expect(detail).toContainText('原請求額：￥100,000');
  expect(await (await page.request.get(`/api/collections/${invoice.id}/history`)).json()).toEqual(history);
  await page.getByRole('button', { name: '督促対応', exact: true }).click(); await page.getByLabel('基準日（日本の業務日）').fill('2026-10-29'); await page.getByRole('button', { name: '候補を表示', exact: true }).click(); const candidates = page.getByRole('table', { name: '督促候補一覧' }); await expect(candidates).toBeVisible(); let more = true; while (more) { await expect(candidates).not.toContainText(fixture.prefix); more = await page.getByRole('button', { name: '次の50件', exact: true }).isEnabled(); if (more) await nextCandidates(page); }
  await page.getByLabel('基準日（日本の業務日）').fill('2026-10-30'); const response = page.waitForResponse((entry) => entry.url().includes('/api/collections?') && entry.url().includes('asOf=2026-10-30')); await page.getByRole('button', { name: '候補を表示', exact: true }).click(); const first = await (await response).json(); expect(first.items).toHaveLength(50); expect(first.nextCursor).not.toBeNull();
  await page.getByLabel('対応の状態').selectOption('HELD'); const next = page.waitForResponse((entry) => entry.url().includes('/api/collections?') && entry.url().includes('cursor=')); await page.getByRole('button', { name: '次の50件', exact: true }).click(); expect(new URL((await next).url()).searchParams.get('view')).toBe('ACTIONABLE');
  await page.getByLabel('対応の状態').selectOption('ACTIONABLE'); await page.getByRole('button', { name: '候補を表示', exact: true }).click(); await expect(candidates).toBeVisible(); await expect(await findCandidate(page, invoice.invoiceNumber)).toContainText('1日');
  const held = await page.request.post(`/api/collections/${invoice.id}/hold`, { headers: fixture.headers, data: { version: 1, reason: '架空の確認中' } }); expect(held.status()).toBe(200); await page.getByLabel('対応の状態').selectOption('HELD'); await page.getByRole('button', { name: '候補を表示', exact: true }).click(); await expect(candidates).toBeVisible(); const ownHeld = await findCandidate(page, invoice.invoiceNumber); await expect(ownHeld).toContainText('保留中'); await expect(ownHeld).toContainText('2026-11-01');
  const otherContext = await browser.newContext(); try { const other = await otherContext.newPage(); await loginInvoiceUser(other, 'beta-admin@example.test', new URL('/', page.url()).toString()); expect((await other.request.get(`/api/collections/${invoice.id}?asOf=2026-10-30`)).status()).toBe(404); expect((await other.request.get(`/api/collections/${invoice.id}/history`)).status()).toBe(404); } finally { await otherContext.close(); }
  await page.context().clearCookies(); await page.getByRole('button', { name: '候補を表示', exact: true }).click(); await expect(page.getByLabel('パスワード', { exact: true })).toHaveValue(''); await expect(candidates).toHaveCount(0);
});
