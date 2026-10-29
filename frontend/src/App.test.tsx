import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import App from './App';

const session = { username: 'alpha-admin', csrfToken: 'csrf-example', csrfHeaderName: 'X-CSRF-TOKEN' };
const ok = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });

async function login() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('ユーザー名'), 'alpha-admin');
  await user.type(screen.getByLabelText('パスワード'), 'debt_local');
  await user.click(screen.getByRole('button', { name: 'ログイン' }));
  return user;
}

describe('Customer workspace', () => {
  it('keeps login available and explains incorrect credentials', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok({}, 401)));
    render(<App />);
    await login();
    expect(await screen.findByRole('alert')).toHaveTextContent('ユーザー名またはパスワードを確認');
    expect(screen.getByRole('button', { name: 'ログイン' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: '顧客を登録' })).not.toBeInTheDocument();
  });

  it('shows duplicate recovery guidance and preserves entered values without adding a row', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(ok(session)).mockResolvedValueOnce(ok([])).mockResolvedValueOnce(ok({}, 409));
    vi.stubGlobal('fetch', fetch);
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
      method: 'POST', credentials: 'same-origin', headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf-example' }),
    }));
  });

  it('adds only the server-confirmed customer and clears tenant data on logout', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(ok(session)).mockResolvedValueOnce(ok([])).mockResolvedValueOnce(ok({ id: '1', code: 'CUST-002', name: '架空商事', version: 0 }, 201)).mockResolvedValueOnce(new Response(null, { status: 204 })));
    render(<App />);
    const user = await login();
    await user.type(await screen.findByLabelText(/顧客コード/), 'CUST-002');
    await user.type(screen.getByLabelText(/顧客名/), '架空商事');
    await user.click(screen.getByRole('button', { name: '顧客を登録' }));
    expect(await screen.findByRole('status')).toHaveTextContent('架空商事を登録しました');
    expect(within(screen.getByRole('table')).getByText('架空商事')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'ログアウト' }));
    expect(await screen.findByLabelText('パスワード')).toHaveValue('');
    expect(screen.queryByText('架空商事')).not.toBeInTheDocument();
  });

  it('rejects whitespace-only input before sending a mutation', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(ok(session)).mockResolvedValueOnce(ok([]));
    vi.stubGlobal('fetch', fetch);
    render(<App />);
    const user = await login();
    await user.type(await screen.findByLabelText(/顧客コード/), '   ');
    await user.type(screen.getByLabelText(/顧客名/), '架空商事');
    await user.click(screen.getByRole('button', { name: '顧客を登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('空白だけでは登録できません');
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('preserves supplementary Japanese characters through input, request and display', async () => {
    const code = '𠮷'.repeat(50);
    const name = '𠮷'.repeat(200);
    const fetch = vi.fn().mockResolvedValueOnce(ok(session)).mockResolvedValueOnce(ok([])).mockResolvedValueOnce(ok({ id: 'unicode', code, name, version: 0 }, 201));
    vi.stubGlobal('fetch', fetch);
    render(<App />);
    const user = await login();
    const codeInput = await screen.findByLabelText(/顧客コード/);
    const nameInput = screen.getByLabelText(/顧客名/);
    expect(codeInput).not.toHaveAttribute('maxlength');
    expect(nameInput).not.toHaveAttribute('maxlength');
    await user.click(codeInput);
    await user.paste(code);
    await user.click(nameInput);
    await user.paste(name);
    expect(codeInput).toHaveValue(code);
    expect(nameInput).toHaveValue(name);
    await user.click(screen.getByRole('button', { name: '顧客を登録' }));
    expect(await screen.findByRole('status')).toHaveTextContent('登録しました');
    expect(fetch).toHaveBeenLastCalledWith('/api/customers', expect.objectContaining({ body: JSON.stringify({ code, name }) }));
    expect(within(screen.getByRole('table')).getByText(name)).toBeVisible();
  });
});
