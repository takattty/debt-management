import { StrictMode } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { captureInvitationEntry, watchInvitationFragments } from './invitation-entry';
import { invalidInvitationMessage } from './invitation-presentation';
import type { Session } from './api';

const token = 'A'.repeat(43);
const current: Session = { userId: '11111111-1111-1111-1111-111111111101', tenantId: '11111111-1111-1111-1111-111111111111', email: 'admin@example.test', role: 'ADMIN', csrfToken: 'session-csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const preview = { tenantDisplayName: '架空株式会社', maskedEmail: '***@example.test', role: 'ACCOUNTANT', expiresAt: '2026-10-30T12:00:00Z' };
const ok = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

afterEach(() => { window.history.replaceState(null, '', '/'); });

function mock(options: { authenticated?: boolean; role?: Session['role']; issueStatus?: number; inspectStatus?: number; acceptStatus?: number; acceptCode?: string } = {}) {
  const state = { authenticated: options.authenticated ?? false };
  const fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === '/api/session') return state.authenticated ? ok({ ...current, role: options.role ?? 'ADMIN' }) : ok({ code: 'UNAUTHENTICATED' }, 401);
    if (path === '/api/customers') return ok([{ id: '1', code: 'EXISTING', name: '既存顧客情報', version: 0 }]);
    if (path === '/api/csrf') return ok({ csrfToken: 'anonymous-csrf', csrfHeaderName: 'X-CSRF-TOKEN' });
    if (path === '/api/logout') { state.authenticated = false; return new Response(null, { status: 204 }); }
    if (path === '/api/invitations') {
      if (options.issueStatus) return ok({ code: options.issueStatus === 403 ? 'ACCESS_DENIED' : 'INVITATION_UNAVAILABLE', message: 'OTHER_TENANT_PRIVATE_DETAIL', errors: [] }, options.issueStatus);
      return ok({ id: 'invitation', expiresAt: preview.expiresAt, acceptanceUrl: `/accept-invitation#token=${token}` }, 201);
    }
    if (path === '/api/invitations/inspect') return options.inspectStatus ? ok({ code: 'INVITATION_INVALID', message: 'PRIVATE_TOKEN_STATE' }, options.inspectStatus) : ok(preview);
    if (path === '/api/invitations/accept') return options.acceptStatus
      ? ok({ code: options.acceptCode ?? 'INVITATION_UNAVAILABLE', message: 'OTHER_TENANT_PRIVATE_DETAIL' }, options.acceptStatus)
      : ok({ userId: 'new-user', email: 'new@example.test', role: 'ACCOUNTANT' }, 201);
    throw new Error(`Unexpected test endpoint: ${path}`);
  });
  vi.stubGlobal('fetch', fetch);
  return { state, fetch };
}

async function openAdmin() {
  const user = userEvent.setup();
  render(<App />);
  await user.click(await screen.findByRole('button', { name: 'メンバー招待' }));
  return user;
}

function openAcceptance() {
  window.history.replaceState(null, '', `/accept-invitation?token=discard-query#token=${token}`);
  const entry = captureInvitationEntry(window.location, window.history);
  render(<StrictMode><App invitationEntry={entry} /></StrictMode>);
}

async function fillAcceptance(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText('招待されたメールアドレス'), 'new@example.test');
  await user.type(screen.getByLabelText('新しいパスワード'), ' a-new-password-with-spaces ');
}

describe('Member invitation', () => {
  it('captures the fragment and removes all secret URL material before requests', () => {
    window.history.replaceState(null, '', `/accept-invitation?token=discard-query#token=${token}`);
    const entry = captureInvitationEntry(window.location, window.history);
    expect(entry?.token).toBe(token);
    expect(window.location.pathname).toBe('/accept-invitation');
    expect(window.location.hash).toBe(''); expect(window.location.search).toBe('');
  });

  it('captures and removes another invitation fragment delivered in the same document', () => {
    const receive = vi.fn();
    const stop = watchInvitationFragments(receive);
    window.history.replaceState(null, '', `/accept-invitation#token=${token}`);
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    expect(receive).toHaveBeenCalledWith({ token });
    expect(window.location.hash).toBe('');
    stop();
  });

  it('issues an additional administrator invite and provides manual copy when clipboard fails', async () => {
    const { fetch } = mock({ authenticated: true });
    const user = await openAdmin();
    await user.type(screen.getByLabelText('招待先メールアドレス'), 'new-admin@example.test');
    await user.selectOptions(screen.getByRole('combobox', { name: '付与する権限' }), 'ADMIN');
    await user.click(screen.getByRole('button', { name: '招待リンクを作成' }));
    const link = await screen.findByLabelText('招待リンク') as HTMLInputElement;
    expect(link.value).toBe(`${window.location.origin}/accept-invitation#token=${token}`);
    expect(fetch).toHaveBeenCalledWith('/api/invitations', expect.objectContaining({ body: JSON.stringify({ email: 'new-admin@example.test', role: 'ADMIN' }) }));
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('Clipboard unavailable'));
    await user.click(screen.getByRole('button', { name: 'リンクをコピー' }));
    expect(await screen.findByRole('status')).toHaveTextContent('手動でコピー');
    expect(document.activeElement).toBe(link); expect(link.selectionStart).toBe(0); expect(link.selectionEnd).toBe(link.value.length);
    await user.click(screen.getByRole('button', { name: 'リンク表示を閉じる' }));
    expect(screen.queryByLabelText('招待リンク')).not.toBeInTheDocument();
  });

  it('hides invitation controls from an accountant', async () => {
    const { fetch } = mock({ authenticated: true, role: 'ACCOUNTANT' });
    render(<App />);
    expect(await screen.findByText('経理担当者')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'メンバー招待' })).not.toBeInTheDocument();
    expect(fetch.mock.calls.some(([path]) => path === '/api/invitations')).toBe(false);
  });

  it('keeps the existing session when the server denies invitation permission', async () => {
    mock({ authenticated: true, issueStatus: 403 });
    const user = await openAdmin();
    await user.type(screen.getByLabelText('招待先メールアドレス'), 'new@example.test');
    await user.click(screen.getByRole('button', { name: '招待リンクを作成' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('権限がありません');
    expect(screen.getByText(current.email)).toBeVisible();
    expect(screen.queryByLabelText('パスワード')).not.toBeInTheDocument();
  });

  it('does not expose account or tenant information from an issuance conflict', async () => {
    mock({ authenticated: true, issueStatus: 409 });
    const user = await openAdmin();
    await user.type(screen.getByLabelText('招待先メールアドレス'), 'new@example.test');
    await user.click(screen.getByRole('button', { name: '招待リンクを作成' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('招待を処理できませんでした');
    expect(document.body.textContent).not.toContain('OTHER_TENANT_PRIVATE_DETAIL');
  });

  it('inspects anonymously once under StrictMode and keeps token out of UI and request URLs', async () => {
    const { fetch } = mock(); openAcceptance();
    expect(await screen.findByText(preview.tenantDisplayName)).toBeVisible();
    expect(screen.getByText(preview.maskedEmail)).toBeVisible();
    expect(document.body.textContent).not.toContain(token);
    expect(window.location.hash).toBe('');
    expect(fetch.mock.calls.filter(([path]) => path === '/api/invitations/inspect')).toHaveLength(1);
    expect(fetch).toHaveBeenCalledWith('/api/invitations/inspect', expect.objectContaining({ body: JSON.stringify({ token }), headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'anonymous-csrf' }) }));
    expect(fetch.mock.calls.some(([path]) => String(path).includes(token) || path === '/api/customers')).toBe(false);
  });

  it('accepts without trimming the password and requires a separate login', async () => {
    const { fetch } = mock(); const user = userEvent.setup(); openAcceptance();
    await fillAcceptance(user);
    await user.click(screen.getByRole('button', { name: '招待を受けて登録' }));
    expect(await screen.findByRole('status')).toHaveTextContent('アカウントを登録しました');
    expect(screen.queryByLabelText('新しいパスワード')).not.toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith('/api/invitations/accept', expect.objectContaining({ body: JSON.stringify({ token, email: 'new@example.test', password: ' a-new-password-with-spaces ' }) }));
    expect(fetch.mock.calls.some(([path]) => path === '/api/auth/login' || path === '/api/customers')).toBe(false);
    expect(localStorage.length).toBe(0);
  });

  it.each(['invalid', 'expired', 'used'])('shows the same public error for an %s invitation', async () => {
    mock({ inspectStatus: 400 }); openAcceptance();
    expect(await screen.findByRole('alert')).toHaveTextContent(invalidInvitationMessage);
    expect(screen.queryByLabelText('新しいパスワード')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('PRIVATE_TOKEN_STATE');
  });

  it('clears the password and avoids tenant disclosure when acceptance conflicts', async () => {
    mock({ acceptStatus: 409 }); const user = userEvent.setup(); openAcceptance();
    await fillAcceptance(user); await user.click(screen.getByRole('button', { name: '招待を受けて登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(invalidInvitationMessage);
    expect(screen.queryByLabelText('新しいパスワード')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('OTHER_TENANT_PRIVATE_DETAIL');
  });

  it('shows validation recovery and clears the password after a rejected attempt', async () => {
    mock({ acceptStatus: 400, acceptCode: 'VALIDATION_ERROR' }); const user = userEvent.setup(); openAcceptance();
    await fillAcceptance(user); await user.click(screen.getByRole('button', { name: '招待を受けて登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('パスワードの条件');
    expect(screen.getByLabelText('新しいパスワード')).toHaveValue('');
    expect(screen.getByLabelText('招待されたメールアドレス')).toHaveValue('new@example.test');
  });

  it('requires explicit logout before inspecting another account invitation and never loads customer data', async () => {
    const { fetch } = mock({ authenticated: true }); const user = userEvent.setup(); openAcceptance();
    expect(await screen.findByRole('button', { name: 'ログアウトして招待を確認' })).toBeVisible();
    expect(fetch.mock.calls.some(([path]) => path === '/api/invitations/inspect' || path === '/api/customers')).toBe(false);
    await user.click(screen.getByRole('button', { name: 'ログアウトして招待を確認' }));
    expect(await screen.findByLabelText('招待されたメールアドレス')).toBeVisible();
    expect(fetch.mock.calls.some(([path]) => path === '/api/customers')).toBe(false);
  });

  it('checks for another tab login before accepting and does not create a user under that session', async () => {
    const { state, fetch } = mock(); const user = userEvent.setup(); openAcceptance();
    await fillAcceptance(user); state.authenticated = true;
    await user.click(screen.getByRole('button', { name: '招待を受けて登録' }));
    expect(await screen.findByRole('button', { name: 'ログアウトして招待を確認' })).toBeVisible();
    expect(fetch.mock.calls.some(([path]) => path === '/api/invitations/accept')).toBe(false);
    expect(screen.queryByLabelText('新しいパスワード')).not.toBeInTheDocument();
  });

  it('requires reconfirming an expired CSRF context and never automatically resends acceptance', async () => {
    const { fetch } = mock({ acceptStatus: 403, acceptCode: 'CSRF_INVALID' }); const user = userEvent.setup(); openAcceptance();
    await fillAcceptance(user); await user.click(screen.getByRole('button', { name: '招待を受けて登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('操作の有効期限が切れました');
    await user.click(screen.getByRole('button', { name: '招待を再確認' }));
    expect(await screen.findByLabelText('新しいパスワード')).toHaveValue('');
    expect(fetch.mock.calls.filter(([path]) => path === '/api/invitations/accept')).toHaveLength(1);
  });

  it('rejects a missing fragment without requesting private invitation information', async () => {
    const { fetch } = mock(); render(<App invitationEntry={{ token: null }} />);
    expect(await screen.findByRole('alert')).toHaveTextContent(invalidInvitationMessage);
    expect(fetch).not.toHaveBeenCalled();
  });
});
