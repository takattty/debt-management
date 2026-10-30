import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import type { Customer, Session } from './api';

const session: Session = { userId: 'actor', tenantId: 'tenant', email: 'home@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const customer: Customer = { id: 'customer', code: 'HOME1', name: '架空ホーム', version: 0 };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });

describe('Workspace home navigation', () => {
  it.each([201, 409])('removes the native destination during an exclusive operation and restores it after HTTP %i', async (status) => {
    let finish!: (response: Response) => void; let created = false;
    const pending = new Promise<Response>((resolve) => { finish = resolve; });
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/session') return json(session);
      if (String(input) === '/api/customers' && init?.method === 'POST') return pending;
      if (String(input) === '/api/customers') return json(created ? [customer] : []);
      throw new Error(`Unexpected endpoint: ${String(input)}`);
    }));
    const user = userEvent.setup(); render(<App />);
    await screen.findByRole('heading', { name: '顧客を登録' });
    const home = screen.getByRole('link', { name: '債権管理 ホーム' });
    expect(home).toHaveAttribute('href', '/'); expect(home).not.toHaveAttribute('aria-disabled');
    await user.type(screen.getByLabelText(/^顧客コード/), customer.code); await user.type(screen.getByLabelText(/^顧客名/), customer.name);
    await user.click(screen.getByRole('button', { name: '顧客を登録' }));
    expect(home).toHaveAttribute('aria-disabled', 'true'); expect(home).not.toHaveAttribute('href'); expect(home).toHaveAttribute('tabindex', '-1');
    expect(fireEvent.click(home)).toBe(false); fireEvent.keyDown(home, { key: 'Enter' }); await user.tab(); expect(home).not.toHaveFocus();
    expect(screen.getByLabelText(/^顧客コード/)).toHaveValue(customer.code);
    await act(async () => { created = status === 201; finish(status === 201 ? json(customer, status) : json({ code: 'DUPLICATE_CUSTOMER_CODE' }, status)); });
    await waitFor(() => expect(home).toHaveAttribute('href', '/'));
    expect(home).not.toHaveAttribute('aria-disabled'); expect(home).not.toHaveAttribute('tabindex', '-1');
  });
});
