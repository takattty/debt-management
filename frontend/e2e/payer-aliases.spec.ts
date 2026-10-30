import { expect, type Page } from '@playwright/test';
import { test } from './fixtures';
import { loginPaymentUser } from './payment-fixtures';
import { sessionHeaders } from './invoice-fixtures';
import type { Customer, PayerAlias } from '../src/api';

async function prepare(page: Page) {
  const prefix = `ALIAS-${Date.now()}-${Math.floor(Math.random() * 100000)}`; await loginPaymentUser(page);
  for (const suffix of ['A', 'B']) {
    await page.getByLabel('顧客コード', { exact: false }).fill(`${prefix}-${suffix}`); await page.getByLabel('顧客名', { exact: false }).fill(`架空名義対応先 ${prefix}-${suffix}`);
    await page.getByRole('button', { name: '顧客を登録', exact: true }).click(); await expect(page.getByRole('cell', { name: `${prefix}-${suffix}`, exact: true })).toBeVisible();
  }
  const customers: Customer[] = await (await page.request.get('/api/customers')).json();
  const first = customers.find((customer) => customer.code === `${prefix}-A`)!; const second = customers.find((customer) => customer.code === `${prefix}-B`)!;
  await page.getByRole('button', { name: '振込名義の対応', exact: true }).click(); return { prefix, first, second };
}
async function submitRegistration(page: Page, payerName: string, customer: Customer) {
  await page.getByLabel('対応する振込名義', { exact: true }).fill(payerName); await page.getByRole('combobox', { name: '対応先の顧客', exact: true }).selectOption(customer.id);
  const response = page.waitForResponse((entry) => new URL(entry.url()).pathname === '/api/payer-aliases' && entry.request().method() === 'POST');
  await page.getByRole('button', { name: '名義対応を登録', exact: true }).click(); return response;
}
async function register(page: Page, payerName: string, customer: Customer): Promise<PayerAlias> {
  const stored = await submitRegistration(page, payerName, customer); expect(stored.status()).toBe(201); return stored.json();
}
async function detail(page: Page, id: string) {
  await page.getByLabel('対応IDで原対応・無効化履歴を開く', { exact: true }).fill(id); await page.getByRole('button', { name: '対応の詳細を取得', exact: true }).click(); await expect(page.getByLabel('原対応の記録', { exact: true })).toContainText(id);
}
async function activeAliases(page: Page) {
  const items: PayerAlias[] = []; let cursor: string | null = null;
  do {
    const result = await page.request.get(`/api/payer-aliases?limit=100${cursor ? `&cursor=${cursor}` : ''}`); expect(result.status()).toBe(200);
    const current: import('../src/generated/schema').components['schemas']['PayerAliasesPage'] = await result.json(); items.push(...current.items); cursor = current.nextCursor;
  } while (cursor);
  return items;
}

test('manual aliases allow ambiguous names, reject the same active pair, retain deactivated originals and separate tenants', async ({ page, browser }) => {
  const { prefix, first, second } = await prepare(page); const payer = `ｶｸｳ KAKUU kakuu é é ${prefix}`;
  const original = await register(page, `  ${payer}  `, first); expect(original.payerName).toBe(payer);
  const ambiguous = await register(page, payer, second); expect(ambiguous.id).not.toBe(original.id);
  await expect(page.getByText(/同じ名義を複数顧客へ対応させると/)).toBeVisible();
  expect((await submitRegistration(page, payer, first)).status()).toBe(409); await expect(page.getByRole('alert')).toContainText('登録済み'); await expect(page.getByLabel('対応する振込名義', { exact: true })).toHaveValue(payer);
  await detail(page, original.id); await page.getByLabel('無効化の理由', { exact: true }).fill('  架空の顧客対応を見直し  '); await expect(page.getByRole('button', { name: '名義対応の無効化を確定', exact: true })).toBeDisabled();
  await page.getByRole('checkbox', { name: 'この原対応と理由を確認し、無効化する', exact: true }).check(); await page.getByRole('button', { name: '名義対応の無効化を確定', exact: true }).click();
  await expect(page.getByLabel('原対応の記録', { exact: true })).toContainText('無効化済み'); await expect(page.getByLabel('原対応の記録', { exact: true })).toContainText('架空の顧客対応を見直し');
  const active = await activeAliases(page); expect(active.some((item) => item.id === original.id)).toBe(false); expect(active.some((item) => item.id === ambiguous.id)).toBe(true);
  const replacement = await register(page, payer, first); expect(replacement.id).not.toBe(original.id);
  await page.reload(); await page.getByRole('button', { name: '振込名義の対応', exact: true }).click(); await detail(page, original.id);
  const persisted: PayerAlias = await (await page.request.get(`/api/payer-aliases/${original.id}`)).json(); expect(persisted.active).toBe(false); expect(persisted.deactivatedByUserId).toBe(original.createdByUserId); expect(persisted.deactivatedAt).not.toBeNull(); expect(persisted.deactivationReason).toBe('架空の顧客対応を見直し');
  await expect(page.getByLabel('原対応の記録', { exact: true })).toContainText(original.createdByUserId); await expect(page.getByRole('button', { name: '名義対応の無効化を確定', exact: true })).toBeDisabled();
  const otherContext = await browser.newContext(); const other = await otherContext.newPage();
  try {
    await loginPaymentUser(other, 'beta-admin@example.test', new URL('/', page.url()).toString()); expect((await other.request.get(`/api/payer-aliases/${original.id}`)).status()).toBe(404);
    expect((await other.request.post('/api/payer-aliases', { headers: await sessionHeaders(other), data: { payerName: payer, customerId: first.id } })).status()).toBe(404);
    await other.getByRole('button', { name: '振込名義の対応', exact: true }).click(); await other.getByLabel('対応IDで原対応・無効化履歴を開く', { exact: true }).fill(original.id); await other.getByRole('button', { name: '対応の詳細を取得', exact: true }).click();
    await expect(other.getByRole('alert')).toContainText('見つかりません'); await expect(other.getByLabel('原対応の記録', { exact: true })).toHaveCount(0); expect((await activeAliases(other)).some((item) => item.id === replacement.id)).toBe(false);
  } finally { await otherContext.close(); }
});

test('real lost create and deactivation responses recover through GET without resending either POST', async ({ page }) => {
  const { prefix, first, second } = await prepare(page); const headers = await sessionHeaders(page);
  for (let index = 0; index < 52; index++) expect((await page.request.post('/api/payer-aliases', { headers, data: { payerName: `架空の別名義 ${prefix}-${index}`, customerId: second.id } })).status()).toBe(201);
  const raw = `  ｶｸｳ é é ${prefix}  `; let creates = 0; let registered!: PayerAlias;
  await page.route('**/api/payer-aliases', async (route) => {
    if (route.request().method() !== 'POST') { await route.continue(); return; }
    creates++; expect(route.request().postDataJSON().payerName).toBe(raw); const real = await route.fetch(); expect(real.status()).toBe(201); registered = await real.json(); await route.abort('failed');
  });
  await page.getByLabel('対応する振込名義', { exact: true }).fill(raw); await page.getByRole('combobox', { name: '対応先の顧客', exact: true }).selectOption(first.id); await page.getByRole('button', { name: '名義対応を登録', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible(); await expect(page.getByRole('button', { name: '名義対応を登録', exact: true })).toBeDisabled(); await expect(page.getByLabel('対応する振込名義', { exact: true })).toHaveValue(raw);
  const cursors: string[] = []; page.on('request', (entry) => { const url = new URL(entry.url()); if (url.pathname === '/api/payer-aliases' && url.searchParams.has('cursor')) cursors.push(url.searchParams.get('cursor')!); });
  await page.getByRole('button', { name: '登録結果を全ページから確認', exact: true }).click(); const recovered = page.getByRole('table', { name: '登録結果の確認一覧', exact: true }); await expect(recovered).toContainText(registered.id); expect(cursors.length).toBeGreaterThan(0); expect(creates).toBe(1);
  await recovered.getByRole('button', { name: `対応 ${registered.id} の詳細`, exact: true }).click(); await expect(page.getByLabel('原対応の記録', { exact: true })).toContainText(registered.id);
  let deactivations = 0; await page.route(`**/api/payer-aliases/${registered.id}/deactivation`, async (route) => { deactivations++; const real = await route.fetch(); expect(real.status()).toBe(200); await route.abort('failed'); });
  await page.getByLabel('無効化の理由', { exact: true }).fill('架空の応答喪失を確認'); await page.getByRole('checkbox', { name: 'この原対応と理由を確認し、無効化する', exact: true }).check(); await page.getByRole('button', { name: '名義対応の無効化を確定', exact: true }).click(); await expect(page.getByRole('alert')).toBeVisible(); await expect(page.getByRole('button', { name: '名義対応の無効化を確定', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '無効化の最新詳細・処理結果を取得', exact: true }).click(); await expect(page.getByLabel('原対応の記録', { exact: true })).toContainText('無効化済み'); await expect(page.getByLabel('原対応の記録', { exact: true })).toContainText('架空の応答喪失を確認'); await expect(page.getByLabel('無効化の理由', { exact: true })).toHaveValue('架空の応答喪失を確認'); expect(deactivations).toBe(1); expect(creates).toBe(1);
});

test('a real version conflict retains the reason draft and shows the other actor original deactivation', async ({ page, browser }) => {
  const { prefix, first } = await prepare(page); const original = await register(page, `架空の競合名義 ${prefix}`, first); await page.getByLabel('無効化の理由', { exact: true }).fill('入力中の架空理由を保持'); await page.getByRole('checkbox', { name: 'この原対応と理由を確認し、無効化する', exact: true }).check();
  const otherContext = await browser.newContext(); const other = await otherContext.newPage();
  try {
    await loginPaymentUser(other, 'alpha-admin@example.test', new URL('/', page.url()).toString()); expect((await other.request.post(`/api/payer-aliases/${original.id}/deactivation`, { headers: await sessionHeaders(other), data: { version: original.version, reason: '別担当者の架空の無効化理由' } })).status()).toBe(200);
    const conflict = page.waitForResponse((response) => response.url().endsWith(`/${original.id}/deactivation`)); await page.getByRole('button', { name: '名義対応の無効化を確定', exact: true }).click(); expect((await conflict).status()).toBe(409);
    await expect(page.getByRole('alert')).toContainText('他の担当者'); await expect(page.getByLabel('無効化の理由', { exact: true })).toHaveValue('入力中の架空理由を保持'); await expect(page.getByRole('button', { name: '名義対応の無効化を確定', exact: true })).toBeDisabled();
    await page.getByRole('button', { name: '無効化の最新詳細・処理結果を取得', exact: true }).click(); await expect(page.getByLabel('原対応の記録', { exact: true })).toContainText('別担当者の架空の無効化理由'); await expect(page.getByLabel('原対応の記録', { exact: true })).toContainText('無効化済み'); await expect(page.getByLabel('無効化の理由', { exact: true })).toHaveValue('入力中の架空理由を保持');
  } finally { await otherContext.close(); }
});

test('real CSRF rejection and expired session clear alias state without registering the draft', async ({ page }) => {
  const { prefix, first } = await prepare(page); const payer = `架空の失効時名義 ${prefix}`;
  await page.route('**/api/payer-aliases', async (route) => { if (route.request().method() !== 'POST') { await route.continue(); return; } const headers = route.request().headers(); delete headers['x-csrf-token']; await route.continue({ headers }); });
  await page.getByLabel('対応する振込名義', { exact: true }).fill(payer); await page.getByRole('combobox', { name: '対応先の顧客', exact: true }).selectOption(first.id); await page.getByRole('button', { name: '名義対応を登録', exact: true }).click(); await expect(page.getByLabel('パスワード', { exact: true })).toHaveValue(''); await expect(page.getByRole('alert')).toContainText('ログイン');
  await page.unroute('**/api/payer-aliases'); await page.context().clearCookies(); await loginPaymentUser(page); expect((await activeAliases(page)).some((item) => item.payerName === payer)).toBe(false);
  await page.getByRole('button', { name: '振込名義の対応', exact: true }).click(); await expect(page.getByLabel('対応する振込名義', { exact: true })).toHaveValue(''); await page.getByLabel('対応する振込名義', { exact: true }).fill(payer); await page.getByRole('combobox', { name: '対応先の顧客', exact: true }).selectOption(first.id); await page.context().clearCookies(); await page.getByRole('button', { name: '名義対応を登録', exact: true }).click(); await expect(page.getByLabel('パスワード', { exact: true })).toHaveValue(''); await expect(page.getByRole('table', { name: '有効な名義対応一覧', exact: true })).toHaveCount(0);
  await loginPaymentUser(page); expect((await activeAliases(page)).some((item) => item.payerName === payer)).toBe(false);
});
