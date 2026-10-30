import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import type { Customer, Session } from './api';

const session: Session = {
  userId: '11111111-1111-1111-1111-111111111112', tenantId: '11111111-1111-1111-1111-111111111111',
  email: 'alpha-admin@example.test', role: 'ADMIN', csrfToken: 'fresh-session-token', csrfHeaderName: 'X-CSRF-TOKEN',
};
const password = 'debt_local_demo_2026';
const ok = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const noContent = () => new Response(null, { status: 204 });
const existing: Customer = { id: '11111111-1111-1111-1111-111111111113', code: 'EXISTING', name: '既存の架空顧客', version: 0 };

function mockApi(options: { authenticated?: boolean; current?: Session; customers?: Customer[]; loginStatus?: number; createStatus?: number; createCode?: string; expireOnCreate?: boolean; changedSession?: Session } = {}) {
  let authenticated = options.authenticated ?? false;
  let current = options.current ?? session;
  const customers = [...(options.customers ?? [])];
  const fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/csrf') return ok({ csrfToken: 'anonymous-token', csrfHeaderName: 'X-CSRF-TOKEN' });
    if (path === '/api/auth/login') {
      if (options.loginStatus) return ok({}, options.loginStatus);
      authenticated = true; return noContent();
    }
    if (path === '/api/session') return authenticated ? ok(current) : ok({}, 401);
    if (path === '/api/logout') { authenticated = false; return noContent(); }
    if (path === '/api/customers' && init?.method === 'POST') {
      if (options.expireOnCreate) authenticated = false;
      if (options.changedSession) current = options.changedSession;
      if (options.createStatus) return ok({ code: options.createCode ?? (options.createStatus === 403 ? 'CSRF_INVALID' : 'REJECTED') }, options.createStatus);
      const body = JSON.parse(String(init.body)) as Pick<Customer, 'code' | 'name'>;
      const created = { id: '11111111-1111-1111-1111-111111111114', ...body, version: 0 };
      customers.push(created); return ok(created, 201);
    }
    if (path === '/api/customers') return authenticated ? ok(customers) : ok({}, 401);
    throw new Error(`Unexpected test endpoint: ${path}`);
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

async function login() {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('メールアドレス'), session.email);
  await user.type(screen.getByLabelText('パスワード'), password);
  await user.click(screen.getByRole('button', { name: 'ログイン' }));
  return user;
}

describe('Customer workspace with cookie authentication', () => {
  it('keeps login available and explains incorrect credentials without identifying an account', async () => {
    mockApi({ loginStatus: 401 });
    render(<App />);
    await login();
    expect(await screen.findByRole('alert')).toHaveTextContent('メールアドレスまたはパスワードを確認');
    expect(screen.getByRole('button', { name: 'ログイン' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: '顧客を登録' })).not.toBeInTheDocument();
  });

  it('shows duplicate recovery guidance and preserves entered values without adding a row', async () => {
    const fetch = mockApi({ createStatus: 409, createCode: 'DUPLICATE_CUSTOMER_CODE' });
    render(<App />);
    const user = await login();
    await user.type(await screen.findByLabelText(/顧客コード/), 'CUST-001');
    await user.type(screen.getByLabelText(/顧客名/), '株式会社サンプル');
    await user.click(screen.getByRole('button', { name: '顧客を登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('別のコードを入力');
    expect(screen.getByLabelText(/顧客コード/)).toHaveValue('CUST-001');
    expect(screen.getByLabelText(/顧客名/)).toHaveValue('株式会社サンプル');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(fetch).toHaveBeenLastCalledWith('/api/customers', expect.objectContaining({
      method: 'POST', credentials: 'same-origin', headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'fresh-session-token' }),
    }));
  });

  it('uses fresh CSRF after login, sends password only at login and clears data on server logout', async () => {
    const fetch = mockApi();
    render(<App />);
    const user = await login();
    await user.type(await screen.findByLabelText(/顧客コード/), 'CUST-002');
    await user.type(screen.getByLabelText(/顧客名/), '架空商事');
    await user.click(screen.getByRole('button', { name: '顧客を登録' }));
    expect(await screen.findByRole('status')).toHaveTextContent('架空商事を登録しました');
    expect(within(screen.getByRole('table')).getByText('架空商事')).toBeVisible();
    expect(fetch).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({
      body: JSON.stringify({ email: session.email, password }), headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'anonymous-token' }),
    }));
    for (const [path, init] of fetch.mock.calls as [string, RequestInit][]) {
      expect(new Headers(init.headers).has('Authorization')).toBe(false);
      expect(init.credentials).toBe('same-origin');
      if (path !== '/api/auth/login') expect(String(init.body)).not.toContain(password);
    }
    expect(localStorage.length).toBe(0);
    await user.click(screen.getByRole('button', { name: 'ログアウト' }));
    expect(await screen.findByLabelText('パスワード')).toHaveValue('');
    expect(screen.queryByText('架空商事')).not.toBeInTheDocument();
    expect(fetch).toHaveBeenLastCalledWith('/api/logout', expect.objectContaining({ headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'fresh-session-token' }) }));
  });

  it('rejects whitespace-only input before sending a mutation', async () => {
    const fetch = mockApi();
    render(<App />);
    const user = await login();
    await user.type(await screen.findByLabelText(/顧客コード/), '   ');
    await user.type(screen.getByLabelText(/顧客名/), '架空商事');
    await user.click(screen.getByRole('button', { name: '顧客を登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('空白だけでは登録できません');
    expect(fetch.mock.calls.filter(([path, init]) => path === '/api/customers' && init.method === 'POST')).toHaveLength(0);
  });

  it('preserves supplementary Japanese characters through input, request and display', async () => {
    const code = '𠮷'.repeat(50);
    const name = '𠮷'.repeat(200);
    const fetch = mockApi();
    render(<App />);
    const user = await login();
    const codeInput = await screen.findByLabelText(/顧客コード/);
    const nameInput = screen.getByLabelText(/顧客名/);
    expect(codeInput).not.toHaveAttribute('maxlength');
    expect(nameInput).not.toHaveAttribute('maxlength');
    await user.click(codeInput); await user.paste(code);
    await user.click(nameInput); await user.paste(name);
    expect(codeInput).toHaveValue(code); expect(nameInput).toHaveValue(name);
    await user.click(screen.getByRole('button', { name: '顧客を登録' }));
    expect(await screen.findByRole('status')).toHaveTextContent('登録しました');
    expect(fetch).toHaveBeenLastCalledWith('/api/customers', expect.objectContaining({ body: JSON.stringify({ code, name }) }));
    expect(within(screen.getByRole('table')).getByText(name)).toBeVisible();
  });

  it('restores authenticated email, role and customers from the server without another login', async () => {
    const current = { ...session, email: 'alpha-accountant@example.test', role: 'ACCOUNTANT' as const };
    const fetch = mockApi({ authenticated: true, current, customers: [existing] });
    render(<App />);
    expect(await screen.findByText(current.email)).toBeVisible();
    expect(screen.getByText('経理担当者')).toBeVisible();
    expect(within(screen.getByRole('table')).getByText(existing.name)).toBeVisible();
    expect(screen.queryByLabelText('パスワード')).not.toBeInTheDocument();
    expect(fetch.mock.calls.some(([path]) => path === '/api/auth/login')).toBe(false);
  });

  it.each([401, 403])('clears tenant data after expired authentication returns %s and does not retry the mutation', async (status) => {
    const fetch = mockApi({ authenticated: true, customers: [existing], createStatus: status, expireOnCreate: true });
    render(<App />);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/顧客コード/), 'EXPIRED');
    await user.type(screen.getByLabelText(/顧客名/), '失効後の架空顧客');
    await user.click(screen.getByRole('button', { name: '顧客を登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('ログインの有効期限が切れました');
    expect(screen.getByLabelText('パスワード')).toHaveValue('');
    expect(screen.queryByText(existing.name)).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path, init]) => path === '/api/customers' && init.method === 'POST')).toHaveLength(1);
  });

  it('clears old tenant data when another browser tab changes the authenticated account', async () => {
    const changedSession = { ...session, userId: '22222222-2222-2222-2222-222222222223', tenantId: '22222222-2222-2222-2222-222222222222', email: 'beta-admin@example.test' };
    mockApi({ authenticated: true, customers: [existing], createStatus: 403, changedSession });
    render(<App />);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/顧客コード/), 'CHANGED');
    await user.type(screen.getByLabelText(/顧客名/), '架空顧客');
    await user.click(screen.getByRole('button', { name: '顧客を登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('ログインの有効期限が切れました');
    expect(screen.queryByText(existing.name)).not.toBeInTheDocument();
    expect(screen.queryByText(changedSession.email)).not.toBeInTheDocument();
  });

  it('keeps a valid session and customer data when an operation is denied by role', async () => {
    const fetch = mockApi({ authenticated: true, customers: [existing], createStatus: 403, createCode: 'ACCESS_DENIED' });
    render(<App />);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/顧客コード/), 'DENIED');
    await user.type(screen.getByLabelText(/顧客名/), '権限拒否の架空顧客');
    await user.click(screen.getByRole('button', { name: '顧客を登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('この操作を行う権限がありません');
    expect(screen.getByText(session.email)).toBeVisible();
    expect(within(screen.getByRole('table')).getByText(existing.name)).toBeVisible();
    expect(screen.queryByLabelText('パスワード')).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path, init]) => path === '/api/customers' && init.method === 'POST')).toHaveLength(1);
  });

  it('explains rate limiting and keeps the login form usable', async () => {
    mockApi({ loginStatus: 429 });
    render(<App />);
    await login();
    expect(await screen.findByRole('alert')).toHaveTextContent('時間をおいて');
    expect(screen.getByRole('button', { name: 'ログイン' })).toBeEnabled();
  });

  it('shows a connection failure while restoring without displaying stale customer data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Connection unavailable')));
    render(<App />);
    expect(await screen.findByRole('alert')).toHaveTextContent('サーバーに接続できません');
    expect(screen.getByLabelText('メールアドレス')).toBeVisible();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});
