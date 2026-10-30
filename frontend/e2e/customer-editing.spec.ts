import { expect, type Page } from '@playwright/test';
import { test } from './fixtures';

const password = process.env.LOCAL_SEED_PASSWORD ?? 'debt_local_demo_2026';

async function login(page: Page, email: string, url = '/') {
  await page.goto(url);
  await page.getByLabel('メールアドレス').fill(email);
  await page.getByLabel('パスワード').fill(password);
  await page.getByRole('button', { name: 'ログイン', exact: true }).click();
  await expect(page.getByRole('heading', { name: '顧客を登録' })).toBeVisible();
}

test('two staff edit the same customer: stale save preserves draft until latest values are reviewed', async ({ page, browser }) => {
  const code = `EDIT-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const draftName = `担当者Aの架空名称 ${code}`;
  const otherName = `担当者Bの架空名称 ${code}`;
  await login(page, 'alpha-accountant@example.test');
  await page.getByLabel('顧客コード', { exact: false }).fill(code);
  await page.getByLabel('顧客名', { exact: false }).fill(`元の架空名称 ${code}`);
  await page.getByRole('button', { name: '顧客を登録', exact: true }).click();
  await expect(page.getByRole('cell', { name: code, exact: true })).toBeVisible();
  const records = await (await page.request.get('/api/customers')).json();
  const stored = records.find((record: { code: string }) => record.code === code);
  await page.getByRole('button', { name: `顧客 ${code} を編集`, exact: true }).click();
  await page.getByLabel('編集中の顧客名', { exact: true }).fill(draftName);

  const otherContext = await browser.newContext();
  const other = await otherContext.newPage();
  try {
    await login(other, 'alpha-admin@example.test', new URL('/', page.url()).toString());
    await other.getByRole('button', { name: `顧客 ${code} を編集`, exact: true }).click();
    await other.getByLabel('編集中の顧客名', { exact: true }).fill(otherName);
    await other.getByRole('button', { name: '変更を保存', exact: true }).click();
    await expect(other.getByRole('cell', { name: otherName, exact: true })).toBeVisible();

    const rejected = page.waitForResponse((response) => response.url().endsWith(`/api/customers/${stored.id}`) && response.request().method() === 'PUT');
    await page.getByRole('button', { name: '変更を保存', exact: true }).click();
    expect((await rejected).status()).toBe(409);
    await expect(page.getByRole('alert')).toContainText('他の担当者が更新しました');
    await expect(page.getByLabel('編集中の顧客名', { exact: true })).toHaveValue(draftName);
    await expect(page.getByRole('button', { name: '変更を保存', exact: true })).toBeDisabled();
    await page.getByRole('button', { name: '最新内容を取得', exact: true }).click();
    await expect(page.getByRole('heading', { name: '最新の登録内容', exact: true })).toBeFocused();
    await expect(page.getByRole('definition').filter({ hasText: otherName })).toBeVisible();
    await expect(page.getByLabel('編集中の顧客名', { exact: true })).toHaveValue(draftName);
    await expect(page.getByRole('button', { name: '変更を保存', exact: true })).toBeDisabled();
    await page.getByRole('checkbox', { name: '最新の登録内容を確認し、入力中の内容で更新する', exact: true }).check();
    const retried = page.waitForResponse((response) => response.url().endsWith(`/api/customers/${stored.id}`) && response.request().method() === 'PUT');
    await page.getByRole('button', { name: '変更を保存', exact: true }).click();
    const updated = await retried;
    expect(updated.status()).toBe(200);
    expect(updated.request().postDataJSON()).toEqual({ code, name: draftName, version: 1 });
    await expect(page.getByRole('cell', { name: draftName, exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('cell', { name: draftName, exact: true })).toBeVisible();
    const after = await (await page.request.get('/api/customers')).json();
    expect(after.find((record: { id: string }) => record.id === stored.id).version).toBe(2);
  } finally { await otherContext.close(); }
});
