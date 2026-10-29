import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { startBrowserApplication } from './browser-application';

const fakeToken = 'A'.repeat(43);
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
let stop: (() => void) | undefined;
let container: HTMLDivElement | undefined;

function fixture(authenticated: boolean) {
  const fetch = vi.fn().mockImplementation(async (path: string) => {
    if (path === '/api/session') return authenticated ? response({ userId: 'actor', tenantId: 'tenant', email: 'admin@example.test', role: 'ADMIN', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' }) : response({ code: 'UNAUTHENTICATED' }, 401);
    if (path === '/api/customers') return response([]);
    if (path === '/api/csrf') return response({ csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' });
    if (path === '/api/invitations') return response({ id: 'invitation', expiresAt: '2026-10-30T12:00:00Z', acceptanceUrl: `/accept-invitation#token=${fakeToken}` }, 201);
    if (path === '/api/invitations/inspect') return response({ tenantDisplayName: '架空株式会社', maskedEmail: '***@example.test', role: 'ACCOUNTANT', expiresAt: '2026-10-30T12:00:00Z' });
    throw new Error('Unexpected lifecycle test request');
  });
  vi.stubGlobal('fetch', fetch);
  container = document.createElement('div'); document.body.append(container);
  act(() => { stop = startBrowserApplication(container!); });
  return fetch;
}

function hideSynchronously() {
  act(() => {
    window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true }));
    // Assert before React's act flush: an asynchronous state reset is insufficient.
    expect(container?.childElementCount).toBe(0);
  });
}

function restoreFromCache() {
  act(() => { window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })); });
}

afterEach(() => {
  act(() => { stop?.(); }); stop = undefined;
  container?.remove(); container = undefined;
  window.history.replaceState(null, '', '/');
});

describe('Browser page lifecycle', () => {
  it('synchronously removes an issued bearer link on pagehide and boots without it on restore', async () => {
    window.history.replaceState(null, '', '/'); fixture(true);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'メンバー招待' }));
    await user.type(screen.getByLabelText('招待先メールアドレス'), 'new@example.test');
    await user.click(screen.getByRole('button', { name: '招待リンクを作成' }));
    expect(await screen.findByLabelText('招待リンク')).toBeVisible();
    hideSynchronously(); restoreFromCache();
    expect(await screen.findByRole('heading', { name: '顧客を登録' })).toBeVisible();
    expect(screen.queryByLabelText('招待リンク')).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain(fakeToken);
  });

  it('discards anonymous invitation token, form and password before restoring the document', async () => {
    window.history.replaceState(null, '', `/accept-invitation#token=${fakeToken}`);
    const fetch = fixture(false); const user = userEvent.setup();
    await user.type(await screen.findByLabelText('招待されたメールアドレス'), 'new@example.test');
    await user.type(screen.getByLabelText('新しいパスワード'), 'a-private-test-password');
    expect(window.location.hash).toBe('');
    hideSynchronously(); restoreFromCache();
    expect(await screen.findByRole('alert')).toHaveTextContent('この招待は利用できません');
    expect(screen.queryByLabelText('新しいパスワード')).not.toBeInTheDocument();
    expect(fetch.mock.calls.filter(([path]) => path === '/api/invitations/inspect')).toHaveLength(1);
  });
});
