import { expect, type Page } from '@playwright/test';
import { test } from './fixtures';
import { collectionFixtures, openCollectionFromInvoice } from './collection-fixtures';
import { loginInvoiceUser } from './invoice-fixtures';
import type { components } from '../src/generated/schema';
type Candidates = components['schemas']['CollectionsPage'];


// Wait for the exact applied query/cursor and its rendered rows before branching.
async function rendered(page: Page, result: Candidates) {
  const table = page.getByRole('table', { name: '督促候補一覧', exact: true }); await expect(table.getByRole('row')).toHaveCount(result.items.length + 1);
  for (const item of result.items) await expect(table.getByRole('cell', { name: item.invoiceNumber, exact: true })).toBeVisible();
}
async function loadCandidates(page: Page, asOf: string, view: string, cursor?: string): Promise<Candidates> {
  const pending = page.waitForResponse((entry) => { const url = new URL(entry.url()); return url.pathname === '/api/collections' && url.searchParams.get('asOf') === asOf && url.searchParams.get('view') === view && (cursor ? url.searchParams.get('cursor') === cursor : !url.searchParams.has('cursor')); });
  await page.getByRole('button', { name: cursor ? '次の50件' : '候補を表示', exact: true }).click(); const response = await pending; expect(response.status()).toBe(200); const result: Candidates = await response.json(); await rendered(page, result); return result;
}
async function findCandidate(page: Page, current: Candidates, number: string, asOf: string, view: string) {
  while (!current.items.some((item) => item.invoiceNumber === number)) { expect(current.nextCursor).not.toBeNull(); current = await loadCandidates(page, asOf, view, current.nextCursor!); }
  return page.getByRole('table', { name: '督促候補一覧' }).getByRole('row').filter({ has: page.getByRole('cell', { name: number, exact: true }) });
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
  await page.getByRole('button', { name: '督促対応', exact: true }).click(); await page.getByLabel('基準日（日本の業務日）').fill('2026-10-29');
  const candidates = page.getByRole('table', { name: '督促候補一覧' }); let current = await loadCandidates(page, '2026-10-29', 'ACTIONABLE');
  while (true) { await expect(candidates).not.toContainText(fixture.prefix); if (!current.nextCursor) break; current = await loadCandidates(page, '2026-10-29', 'ACTIONABLE', current.nextCursor); }
  await page.getByLabel('基準日（日本の業務日）').fill('2026-10-30'); const first = await loadCandidates(page, '2026-10-30', 'ACTIONABLE'); expect(first.items).toHaveLength(50); expect(first.nextCursor).not.toBeNull();
  await page.getByLabel('対応の状態').selectOption('HELD'); await loadCandidates(page, '2026-10-30', 'ACTIONABLE', first.nextCursor!);
  await page.getByLabel('対応の状態').selectOption('ACTIONABLE'); current = await loadCandidates(page, '2026-10-30', 'ACTIONABLE'); await expect(await findCandidate(page, current, invoice.invoiceNumber, '2026-10-30', 'ACTIONABLE')).toContainText('1日');
  const held = await page.request.post(`/api/collections/${invoice.id}/hold`, { headers: fixture.headers, data: { version: 1, reason: '架空の確認中' } }); expect(held.status()).toBe(200); await page.getByLabel('対応の状態').selectOption('HELD'); current = await loadCandidates(page, '2026-10-30', 'HELD'); const ownHeld = await findCandidate(page, current, invoice.invoiceNumber, '2026-10-30', 'HELD'); await expect(ownHeld).toContainText('保留中'); await expect(ownHeld).toContainText('2026-11-01');
  const otherContext = await browser.newContext(); try { const other = await otherContext.newPage(); await loginInvoiceUser(other, 'beta-admin@example.test', new URL('/', page.url()).toString()); expect((await other.request.get(`/api/collections/${invoice.id}?asOf=2026-10-30`)).status()).toBe(404); expect((await other.request.get(`/api/collections/${invoice.id}/history`)).status()).toBe(404); } finally { await otherContext.close(); }
  await page.context().clearCookies(); await page.getByRole('button', { name: '候補を表示', exact: true }).click(); await expect(page.getByLabel('パスワード', { exact: true })).toHaveValue(''); await expect(candidates).toHaveCount(0);
});

for (const cancel of [false, true]) {
  test(`nested invoice ${cancel ? 'cancellation' : 'correction'} keeps parent close/navigation blocked until the real response and refreshed balance`, async ({ page }) => {
    test.setTimeout(90000);
    const fixture = await collectionFixtures(page);
    const invoice = fixture.invoices[0];
    const detail = await openCollectionFromInvoice(page, fixture.customer, invoice);
    await detail.getByRole('button', { name: '原請求・訂正履歴を確認', exact: true }).click();
    const editor = detail.getByRole('region', { name: `請求の訂正・履歴：${invoice.invoiceNumber}`, exact: true });
    await expect(editor).toBeVisible();

    const mutationPath = `/api/invoices/${invoice.id}${cancel ? '/cancel' : ''}`;
    const matchesMutation = (url: URL) => url.pathname === mutationPath;
    let release!: () => void;
    let captured!: () => void;
    let completed!: () => void;
    const responseGate = new Promise<void>((resolve) => { release = resolve; });
    const responseCaptured = new Promise<void>((resolve) => { captured = resolve; });
    const routeCompleted = new Promise<void>((resolve) => { completed = resolve; });
    let routeStarted = false;
    let routeFailed = false;
    let realStatus: number | null = null;
    await page.route(matchesMutation, async (route) => {
      routeStarted = true;
      try {
        // Commit through the real API; hold only its real response to the browser.
        const response = await route.fetch();
        realStatus = response.status();
        captured();
        await responseGate;
        await route.fulfill({ response });
      } catch {
        routeFailed = true;
        captured();
        await route.abort().catch(() => {});
      } finally { completed(); }
    });
    try {
      if (cancel) {
        await editor.getByLabel('取消理由', { exact: true }).fill(`実応答保留の取消 ${fixture.prefix}`);
        await editor.getByRole('checkbox', { name: 'この請求の最新内容と取消の影響を確認し、取り消す', exact: true }).check();
      } else {
        await editor.getByLabel('訂正後の請求額（円）', { exact: true }).fill('120000');
        await editor.getByLabel('訂正理由', { exact: true }).fill(`実応答保留の訂正 ${fixture.prefix}`);
      }
      const submit = editor.getByRole('button', { name: cancel ? '請求の取消を確定' : '訂正を保存', exact: true });
      await submit.click();
      await responseCaptured;
      expect(routeFailed).toBe(false); expect(realStatus).toBe(200);
      await expect(submit).toBeDisabled();
      await expect(detail.getByRole('button', { name: '対応を閉じる', exact: true })).toBeDisabled();
      await expect(detail.getByRole('button', { name: '原請求を閉じる', exact: true })).toBeDisabled();
      await expect(page.getByRole('button', { name: '顧客管理', exact: true })).toBeDisabled();

      const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/collections/${invoice.id}` && response.request().method() === 'GET');
      release(); await routeCompleted;
      expect(routeFailed).toBe(false); expect((await refreshed).status()).toBe(200);
      await expect(editor).toHaveCount(0);
      await expect(detail).toContainText(cancel ? '現在の未回収額：￥0' : '現在の未回収額：￥120,000');
      if (cancel) await expect(detail).toContainText('取消済み・対象外');
      await expect(detail.getByRole('button', { name: '対応を閉じる', exact: true })).toBeEnabled();
      await expect(page.getByRole('button', { name: '顧客管理', exact: true })).toBeEnabled();
      await detail.getByRole('button', { name: '対応を閉じる', exact: true }).click();
      await expect(detail).toHaveCount(0);
    } finally {
      release();
      if (routeStarted) await routeCompleted;
      await page.unroute(matchesMutation);
    }
  });
}
