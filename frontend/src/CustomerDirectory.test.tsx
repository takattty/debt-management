import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import type { Customer, Session } from './api';

const customer: Customer = { id: '11111111-1111-1111-1111-111111111150', code: 'EDIT-001', name: '架空の元の顧客', version: 0 };
const session: Session = { userId: '11111111-1111-1111-1111-111111111112', tenantId: '11111111-1111-1111-1111-111111111111', email: 'alpha-accountant@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });

function mockServer(options: { firstFailure?: { status: number; code: string }; reloadFails?: boolean; lostResponse?: boolean; missing?: boolean } = {}) {
  let current = { ...customer };
  let updates = 0;
  let reads = 0;
  const fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/session') return json(session);
    if (path === '/api/customers') {
      reads++;
      if (options.reloadFails && reads === 2) return json({}, 503);
      return json(options.missing && updates > 0 ? [] : [current]);
    }
    if (path === `/api/customers/${customer.id}` && init?.method === 'PUT') {
      updates++;
      if (options.firstFailure && updates === 1) {
        if (options.firstFailure.code === 'VERSION_CONFLICT') current = { ...current, name: '別担当者の登録内容', version: 1 };
        return json({ code: options.firstFailure.code, errors: [] }, options.firstFailure.status);
      }
      const body = JSON.parse(String(init.body));
      current = { ...current, code: body.code, name: body.name, version: body.version + 1 };
      if (options.lostResponse && updates === 1) throw new TypeError('Response lost');
      return json(current);
    }
    throw new Error(`Unexpected endpoint: ${path}`);
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

async function edit() {
  const user = userEvent.setup();
  render(<App />);
  await user.click(await screen.findByRole('button', { name: `顧客 ${customer.code} を編集` }));
  return user;
}

async function changeName(user: ReturnType<typeof userEvent.setup>, name = '自分が入力した名称') {
  await user.clear(screen.getByLabelText('編集中の顧客名'));
  await user.type(screen.getByLabelText('編集中の顧客名'), name);
}

describe('Versioned customer editing', () => {
  it('uses the generated update operation with expected version and rereads the stored list after success', async () => {
    const fetch = mockServer();
    const user = await edit();
    expect(screen.getByLabelText('編集中の顧客コード')).toHaveFocus();
    await changeName(user);
    await user.click(screen.getByRole('button', { name: '変更を保存' }));
    expect(await screen.findByRole('status')).toHaveTextContent('自分が入力した名称を更新しました');
    expect(within(screen.getByRole('table')).getByText('自分が入力した名称')).toBeVisible();
    expect(fetch.mock.calls.filter(([path]) => path === '/api/customers')).toHaveLength(2);
    const update = fetch.mock.calls.find(([path]) => path === `/api/customers/${customer.id}`)?.[1];
    expect(JSON.parse(update.body)).toEqual({ code: customer.code, name: '自分が入力した名称', version: 0 });
    expect(update.headers['X-CSRF-TOKEN']).toBe(session.csrfToken);
  });

  it('preserves the draft after a conflict and requires current-value review before a version1 retry', async () => {
    const fetch = mockServer({ firstFailure: { status: 409, code: 'VERSION_CONFLICT' } });
    const user = await edit();
    await changeName(user);
    await user.click(screen.getByRole('button', { name: '変更を保存' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('他の担当者が更新しました');
    expect(screen.getByLabelText('編集中の顧客名')).toHaveValue('自分が入力した名称');
    expect(screen.getByRole('button', { name: '変更を保存' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '最新内容を取得' }));
    expect(await screen.findByRole('heading', { name: '最新の登録内容' })).toHaveFocus();
    expect(screen.getAllByText('別担当者の登録内容')).toHaveLength(2);
    expect(screen.getByLabelText('編集中の顧客名')).toHaveValue('自分が入力した名称');
    expect(screen.getByRole('button', { name: '変更を保存' })).toBeDisabled();
    expect(fetch.mock.calls.filter(([path]) => String(path).includes(customer.id))).toHaveLength(1);
    await user.click(screen.getByRole('checkbox', { name: '最新の登録内容を確認し、入力中の内容で更新する' }));
    await user.click(screen.getByRole('button', { name: '変更を保存' }));
    expect(await screen.findByRole('status')).toHaveTextContent('自分が入力した名称を更新しました');
    const updates = fetch.mock.calls.filter(([path]) => String(path).includes(customer.id));
    expect(JSON.parse(updates[1][1].body).version).toBe(1);
  });

  it('recovers a successful write with a failed list read without resending PUT', async () => {
    const fetch = mockServer({ reloadFails: true });
    const user = await edit();
    await changeName(user);
    await user.click(screen.getByRole('button', { name: '変更を保存' }));
    expect(await screen.findByText(/更新は完了しています/)).toBeVisible();
    expect(screen.getByRole('button', { name: '変更を保存' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '一覧を再取得して閉じる' }));
    expect(await screen.findByRole('status')).toHaveTextContent('更新しました');
    expect(fetch.mock.calls.filter(([path]) => String(path).includes(customer.id))).toHaveLength(1);
  });

  it('requires a read after a lost response without automatically resending the update', async () => {
    const fetch = mockServer({ lostResponse: true });
    const user = await edit();
    await changeName(user);
    await user.click(screen.getByRole('button', { name: '変更を保存' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('サーバーに接続できません');
    expect(screen.getByRole('button', { name: '変更を保存' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '最新内容を取得' }));
    expect(await screen.findByRole('heading', { name: '最新の登録内容' })).toBeVisible();
    expect(fetch.mock.calls.filter(([path]) => String(path).includes(customer.id))).toHaveLength(1);
  });

  it.each([{ status: 401, code: 'UNAUTHENTICATED' }, { status: 403, code: 'CSRF_INVALID' }])('clears the workspace after session expiry %j', async (firstFailure) => {
    mockServer({ firstFailure });
    const user = await edit();
    await user.click(screen.getByRole('button', { name: '変更を保存' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('ログインの有効期限が切れました');
    expect(screen.getByLabelText('パスワード')).toHaveValue('');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('distinguishes duplicate code from stale version, retaining the editable draft', async () => {
    mockServer({ firstFailure: { status: 409, code: 'DUPLICATE_CUSTOMER_CODE' } });
    const user = await edit();
    await changeName(user);
    await user.click(screen.getByRole('button', { name: '変更を保存' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('別のコードを入力');
    expect(screen.getByLabelText('編集中の顧客名')).toHaveValue('自分が入力した名称');
    expect(screen.getByRole('button', { name: '変更を保存' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: '最新内容を取得' })).not.toBeInTheDocument();
  });

  it('keeps the session and draft after role denial without resending', async () => {
    const fetch = mockServer({ firstFailure: { status: 403, code: 'ACCESS_DENIED' } });
    const user = await edit();
    await changeName(user);
    await user.click(screen.getByRole('button', { name: '変更を保存' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('この操作を行う権限がありません');
    expect(screen.getByText(session.email)).toBeVisible();
    expect(screen.getByLabelText('編集中の顧客名')).toHaveValue('自分が入力した名称');
    expect(fetch.mock.calls.filter(([path]) => String(path).includes(customer.id))).toHaveLength(1);
  });

  it('hides unavailable customer details and keeps saving blocked after a404 and missing reload result', async () => {
    mockServer({ firstFailure: { status: 404, code: 'NOT_FOUND' }, missing: true });
    const user = await edit();
    await user.click(screen.getByRole('button', { name: '変更を保存' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('顧客が見つかりません');
    await user.click(screen.getByRole('button', { name: '最新内容を取得' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('編集を閉じて一覧を確認');
    expect(screen.getByRole('button', { name: '変更を保存' })).toBeDisabled();
  });

  it('supports Escape cancellation and discards draft on workspace navigation', async () => {
    mockServer();
    const user = await edit();
    await changeName(user);
    await user.keyboard('{Escape}');
    expect(screen.queryByLabelText('編集中の顧客名')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: `顧客 ${customer.code} を編集` })).toHaveFocus();
    await user.click(screen.getByRole('button', { name: `顧客 ${customer.code} を編集` }));
    expect(screen.getByLabelText('編集中の顧客名')).toHaveValue(customer.name);
    await user.click(screen.getByRole('button', { name: '顧客CSV取込' }));
    await user.click(screen.getByRole('button', { name: '顧客管理' }));
    expect(screen.queryByLabelText('編集中の顧客名')).not.toBeInTheDocument();
  });
});
