import { statusFeedback } from './status-feedback';
import { expect, type Page } from '@playwright/test';
import { test } from './fixtures';

const password = process.env.LOCAL_SEED_PASSWORD ?? 'debt_local_demo_2026';
const invitedPassword = 'invited_member_password_2026';

async function login(page: Page, email: string, secret = password) {
  await page.getByLabel('メールアドレス', { exact: true }).fill(email);
  await page.getByLabel('パスワード', { exact: true }).fill(secret);
  await page.getByRole('button', { name: 'ログイン', exact: true }).click();
  await expect(page.getByRole('heading', { name: '顧客を登録', exact: true })).toBeVisible();
}

async function issue(page: Page, email: string, role: 'ADMIN' | 'ACCOUNTANT') {
  await page.getByRole('button', { name: 'メンバー招待', exact: true }).click();
  await page.getByLabel('招待先メールアドレス', { exact: true }).fill(email);
  await page.getByLabel('付与する権限', { exact: true }).selectOption(role);
  await page.getByRole('button', { name: '招待リンクを作成', exact: true }).click();
  const field = page.getByLabel('招待リンク', { exact: true });
  await expect(field).toBeVisible();
  const link = await field.inputValue();
  const url = new URL(link);
  expect(url.pathname).toBe('/accept-invitation'); expect(url.search).toBe('');
  return link;
}

// Pass bearer fragments only as evaluation arguments, outside diagnostic navigation URLs.
async function openInvitation(page: Page, link: string) {
  const navigation = page.waitForEvent('framenavigated', { predicate: (frame) => frame === page.mainFrame() });
  await page.evaluate((target) => { setTimeout(() => window.location.assign(target), 0); }, link);
  await navigation;
  await page.waitForURL((url) => url.pathname === '/accept-invitation' && url.hash === '' && url.search === '');
  await expect(page.getByRole('heading', { name: 'メンバー招待', exact: true })).toBeVisible();
}

async function accept(page: Page, email: string) {
  await page.getByLabel('招待されたメールアドレス', { exact: true }).fill(email);
  await page.getByLabel('新しいパスワード', { exact: true }).fill(invitedPassword);
  await page.getByRole('button', { name: '招待を受けて登録', exact: true }).click();
  await expect(statusFeedback(page, 'アカウントを登録しました')).toContainText('アカウントを登録しました');
  expect((await page.request.get('/api/session')).status()).toBe(401);
}

test('administrator manually invites an accountant; fragment disappears, acceptance is separate from login and role denial retains the session', async ({ page }) => {
  const suffix = `${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const email = `ui-accountant-${suffix}@example.test`;
  await page.goto('/'); await login(page, 'alpha-admin@example.test');
  const link = await issue(page, email, 'ACCOUNTANT');
  await page.evaluate(() => { Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: () => Promise.reject(new Error('Clipboard unavailable')) } }); });
  await page.getByRole('button', { name: 'リンクをコピー', exact: true }).click();
  await expect(statusFeedback(page, '手動でコピー')).toContainText('手動でコピー');
  expect(await page.getByLabel('招待リンク', { exact: true }).evaluate((input: HTMLInputElement) => input.selectionStart === 0 && input.selectionEnd === input.value.length)).toBe(true);
  await openInvitation(page, link);
  await expect(page.getByRole('table')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'ログアウトして招待を確認', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'ログアウトして招待を確認', exact: true }).click();
  await expect(page.getByText('Fictional Alpha', { exact: true })).toBeVisible();
  await expect(page.getByText('***@example.test', { exact: true })).toBeVisible();
  await accept(page, email);
  await page.getByRole('button', { name: 'ログイン画面へ進む', exact: true }).click();
  await login(page, email, invitedPassword);
  await expect(page.getByText('経理担当者', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'メンバー招待', exact: true })).toHaveCount(0);
  const session = await (await page.request.get('/api/session')).json();
  const forbidden = await page.request.post('/api/invitations', { headers: { [session.csrfHeaderName]: session.csrfToken }, data: { email: `denied-${suffix}@example.test`, role: 'ADMIN' } });
  expect(forbidden.status()).toBe(403); expect((await forbidden.json()).code).toBe('ACCESS_DENIED');
  expect((await page.request.get('/api/session')).status()).toBe(200);
  await page.reload(); await expect(page.getByText(email, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'ログアウト', exact: true }).click();
  await openInvitation(page, link);
  const invalidMessage = await page.getByRole('alert').textContent();
  expect(invalidMessage).toContain('この招待は利用できません');
  await page.goto('/accept-invitation#token=invalid');
  await page.waitForURL((url) => url.hash === '');
  await expect(page.getByRole('alert')).toHaveText(invalidMessage!);
  expect(new URL(page.url()).hash).toBe('');
  expect(await page.evaluate(() => localStorage.length)).toBe(0);
});

test('an invited administrator can issue a further member invitation', async ({ page }) => {
  const suffix = `${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const email = `ui-admin-${suffix}@example.test`;
  await page.goto('/'); await login(page, 'alpha-admin@example.test');
  const link = await issue(page, email, 'ADMIN');
  await openInvitation(page, link);
  await page.getByRole('button', { name: 'ログアウトして招待を確認', exact: true }).click();
  await expect(page.getByText('管理者', { exact: true })).toBeVisible();
  await accept(page, email);
  await page.getByRole('button', { name: 'ログイン画面へ進む', exact: true }).click();
  await login(page, email, invitedPassword);
  await expect(page.getByText('管理者', { exact: true })).toBeVisible();
  await issue(page, `ui-next-${suffix}@example.test`, 'ACCOUNTANT');
  await expect(page.getByRole('heading', { name: '招待リンクを作成しました', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'リンク表示を閉じる', exact: true }).click();
  await expect(page.getByLabel('招待リンク', { exact: true })).toHaveCount(0);
});

test('acceptance conflict does not reveal another tenant or retain a password', async ({ page, browser }) => {
  const suffix = `${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const email = `ui-conflict-${suffix}@example.test`;
  await page.goto('/'); await login(page, 'alpha-admin@example.test');
  const alphaLink = await issue(page, email, 'ACCOUNTANT');
  await page.getByRole('button', { name: 'ログアウト', exact: true }).click();
  await login(page, 'beta-admin@example.test');
  const betaLink = await issue(page, email, 'ACCOUNTANT');
  await openInvitation(page, alphaLink);
  await page.getByRole('button', { name: 'ログアウトして招待を確認', exact: true }).click();
  await expect(page.getByLabel('招待されたメールアドレス', { exact: true })).toBeVisible();
  // Keep Alpha's confirmed form open while the other tenant wins the global email race.
  const otherContext = await browser.newContext();
  try {
    const other = await otherContext.newPage();
    await other.goto(new URL('/', page.url()).toString());
    await openInvitation(other, betaLink);
    await accept(other, email);
  } finally { await otherContext.close(); }
  await page.getByLabel('招待されたメールアドレス', { exact: true }).fill(email);
  await page.getByLabel('新しいパスワード', { exact: true }).fill(invitedPassword);
  const conflict = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/invitations/accept' && response.request().method() === 'POST');
  await page.getByRole('button', { name: '招待を受けて登録', exact: true }).click();
  expect((await conflict).status()).toBe(409);
  await expect(page.getByRole('alert')).toContainText('この招待は利用できません');
  await expect(page.getByLabel('新しいパスワード', { exact: true })).toHaveCount(0);
  expect(await page.locator('body').textContent()).not.toContain('Fictional Beta');
});
