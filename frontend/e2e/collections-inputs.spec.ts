import { expect } from '@playwright/test';
import { test } from './fixtures';
import { collectionFixtures, currentCollection, openCollectionFromInvoice } from './collection-fixtures';
import { loginInvoiceUser, sessionHeaders } from './invoice-fixtures';

test('a pristine schedule follows another staff update and GET refresh without clearing the saved date', async ({ page, browser }) => {
  const fixture = await collectionFixtures(page); const invoice = fixture.invoices[0];
  const detail = await openCollectionFromInvoice(page, fixture.customer, invoice);
  const date = detail.getByLabel('次回対応日（空欄で予定を解除）'); await expect(date).toHaveValue('');
  const context = await browser.newContext(); const other = await context.newPage();
  try {
    await loginInvoiceUser(other, 'alpha-admin@example.test', new URL('/', page.url()).toString());
    const headers = await sessionHeaders(other);
    expect((await other.request.post(`/api/collections/${invoice.id}/actions`, { headers, data: { version: 0, note: '別担当者が予定を設定', nextActionDate: '2026-11-09' } })).status()).toBe(200);
    await detail.getByRole('button', { name: '最新状況をGETで再取得', exact: true }).click(); await expect(date).toHaveValue('2026-11-09');
    await detail.getByLabel('対応メモ（共有）').fill('予定は変更せず新しい対応だけを記録');
    const response = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/collections/${invoice.id}/actions` && r.request().method() === 'POST');
    await detail.getByRole('button', { name: '対応メモを記録', exact: true }).click(); const saved = await response;
    expect(saved.status()).toBe(200); expect(saved.request().postDataJSON()).toMatchObject({ version: 1, nextActionDate: '2026-11-09' });
    await expect(detail.getByLabel('対応メモ（共有）')).toHaveValue(''); expect((await currentCollection(page, invoice.id)).nextActionDate).toBe('2026-11-09');
    expect((await other.request.post(`/api/collections/${invoice.id}/actions`, { headers, data: { version: 2, note: '別担当者が再調整', nextActionDate: '2026-11-12' } })).status()).toBe(200);
    await detail.getByRole('button', { name: '最新状況をGETで再取得', exact: true }).click(); await expect(date).toHaveValue('2026-11-12');
  } finally { await context.close(); }
});

test('edited and explicitly cleared schedule drafts survive conflict or refresh until the staff member sends them', async ({ page }) => {
  const fixture = await collectionFixtures(page); const invoice = fixture.invoices[0];
  const detail = await openCollectionFromInvoice(page, fixture.customer, invoice); const date = detail.getByLabel('次回対応日（空欄で予定を解除）');
  await date.fill('2026-11-11'); await detail.getByLabel('対応メモ（共有）').fill('本人が選んだ予定を保持');
  expect((await page.request.post(`/api/collections/${invoice.id}/actions`, { headers: fixture.headers, data: { version: 0, note: '別の画面から更新', nextActionDate: '2026-11-09' } })).status()).toBe(200);
  let posts = 0; page.on('request', (request) => { if (new URL(request.url()).pathname === `/api/collections/${invoice.id}/actions` && request.method() === 'POST') posts++; });
  const rejected = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/collections/${invoice.id}/actions` && r.request().method() === 'POST');
  await detail.getByRole('button', { name: '対応メモを記録', exact: true }).click(); expect((await rejected).status()).toBe(409);
  await detail.getByRole('button', { name: 'GETで結果・最新状況を確認', exact: true }).click();
  await expect(date).toHaveValue('2026-11-11'); await expect(detail.getByLabel('対応メモ（共有）')).toHaveValue('本人が選んだ予定を保持');
  await expect(detail.getByText(/現在の登録予定：2026-11-09/)).toBeVisible(); expect(posts).toBe(1);
  await detail.getByRole('checkbox', { name: '最新状況と原本履歴を読み、入力中の内容を比較しました', exact: true }).check();
  await detail.getByRole('button', { name: '最新の版で再確認に戻る', exact: true }).click();
  const saved = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/collections/${invoice.id}/actions` && r.request().method() === 'POST');
  await detail.getByRole('button', { name: '対応メモを記録', exact: true }).click(); expect((await saved).request().postDataJSON()).toMatchObject({ version: 1, nextActionDate: '2026-11-11' });
  await expect(detail.getByLabel('対応メモ（共有）')).toHaveValue(''); expect(posts).toBe(2);
  await date.clear();
  expect((await page.request.post(`/api/collections/${invoice.id}/actions`, { headers: fixture.headers, data: { version: 2, note: '予定を再調整', nextActionDate: '2026-11-15' } })).status()).toBe(200);
  await detail.getByRole('button', { name: '最新状況をGETで再取得', exact: true }).click(); await expect(date).toHaveValue('');
  await expect(detail.getByText(/現在の登録予定：2026-11-15/)).toBeVisible(); await detail.getByLabel('対応メモ（共有）').fill('予定を明示的に解除');
  const cleared = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/collections/${invoice.id}/actions` && r.request().method() === 'POST');
  await detail.getByRole('button', { name: '対応メモを記録', exact: true }).click(); const result = await cleared;
  expect(result.status()).toBe(200); expect(result.request().postDataJSON()).toMatchObject({ version: 3, nextActionDate: null });
  expect((await currentCollection(page, invoice.id)).nextActionDate).toBeNull();
});

test('browser input accepts full supplementary-character limits and preserves over-limit drafts without sending them', async ({ page }) => {
  const fixture = await collectionFixtures(page); const invoice = fixture.invoices[0]; const detail = await openCollectionFromInvoice(page, fixture.customer, invoice);
  const note = '𠮷'.repeat(5000); const reason = '😀'.repeat(1000); const noteInput = detail.getByLabel('対応メモ（共有）'); const reasonInput = detail.getByLabel('保留・解除の理由');
  const posts: string[] = []; page.on('request', (request) => { const path = new URL(request.url()).pathname; if (request.method() === 'POST' && path.startsWith(`/api/collections/${invoice.id}/`)) posts.push(path); });
  await noteInput.fill(note + '𠮷'); await expect(noteInput).toHaveValue(note + '𠮷'); await expect(noteInput).toHaveAttribute('aria-invalid', 'true');
  await expect(detail.getByRole('button', { name: '対応メモを記録', exact: true })).toBeDisabled(); expect(posts).toHaveLength(0);
  await noteInput.fill(note); await expect(detail.getByText('5,000 / 5,000文字', { exact: true })).toBeVisible();
  const saved = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/collections/${invoice.id}/actions` && r.request().method() === 'POST');
  await detail.getByRole('button', { name: '対応メモを記録', exact: true }).click(); const recorded = await saved;
  expect(recorded.status()).toBe(200); expect(recorded.request().postDataJSON().note).toBe(note); await expect(noteInput).toHaveValue(''); expect((await currentCollection(page, invoice.id)).latestNote).toBe(note);
  await reasonInput.fill(reason + '😀'); await expect(reasonInput).toHaveValue(reason + '😀'); await expect(reasonInput).toHaveAttribute('aria-invalid', 'true');
  await expect(detail.getByRole('button', { name: '理由を記録して保留', exact: true })).toBeDisabled(); expect(posts).toHaveLength(1);
  await reasonInput.fill(reason); await expect(detail.getByText('1,000 / 1,000文字', { exact: true })).toBeVisible();
  const held = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/collections/${invoice.id}/hold` && r.request().method() === 'POST');
  await detail.getByRole('button', { name: '理由を記録して保留', exact: true }).click(); const hold = await held;
  expect(hold.status()).toBe(200); expect(hold.request().postDataJSON()).toEqual({ version: 1, reason }); expect((await currentCollection(page, invoice.id)).holdReason).toBe(reason); expect(posts).toHaveLength(2);
});
