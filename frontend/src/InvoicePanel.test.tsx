import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import InvoicePanel from './InvoicePanel';
import type { Customer, Invoice, InvoiceHistory, Session } from './api';

const customer: Customer = { id: 'customer-a', code: 'C001', name: '架空商事', version: 0 };
const session: Session = { userId: 'actor-a', tenantId: 'tenant-a', email: 'accountant@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const invoice: Invoice = { id: 'invoice-a', customerId: customer.id, customerCode: customer.code, customerName: customer.name, invoiceNumber: 'INV-001', issueDate: '2026-10-30', dueDate: '2026-11-30', amount: 100000, outstandingAmount: 100000, version: 0, status: 'ACTIVE', createdAt: '2026-10-30T00:00:00Z', updatedAt: '2026-10-30T00:00:00Z', cancelledAt: null };
const original: InvoiceHistory = { revision: 0, operation: 'CREATED', reason: null, actorUserId: session.userId, occurredAt: invoice.createdAt, snapshot: { ...invoice, sourceBatchId: 'original-batch' } };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });

function setup(options: { conflict?: boolean; lost?: boolean; paged?: boolean; status?: Invoice['status']; allocated?: boolean; denied?: boolean } = {}) {
  let current = { ...invoice, status: options.status ?? invoice.status, outstandingAmount: options.allocated ? 50000 : invoice.outstandingAmount };
  let writes = 0;
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.includes('/history?')) return json({ items: path.includes('cursor=0&') ? [{ ...original, revision: 1, operation: 'CORRECTED', reason: '架空の過去の訂正' }] : [original], nextCursor: options.paged && !path.includes('cursor=0&') ? 0 : null });
    if (init?.method === 'PUT') {
      writes++;
      if (options.denied) return json({ code: 'ACCESS_DENIED' }, 403);
      if (options.conflict && writes === 1) { current = { ...current, amount: 120000, outstandingAmount: 120000, version: 1 }; return json({ code: 'VERSION_CONFLICT' }, 409); }
      const body = JSON.parse(String(init.body));
      current = { ...current, ...body, outstandingAmount: body.amount, version: body.version + 1 };
      if (options.lost) throw new TypeError('lost response');
      return json(current);
    }
    return json(current);
  });
  vi.stubGlobal('fetch', fetch);
  const expired = vi.fn(); const changed = vi.fn(async () => {});
  render(<InvoicePanel id={invoice.id} customers={[customer]} session={session} onBusyChange={vi.fn()} onSessionExpired={expired} onChanged={changed} onClose={vi.fn()} />);
  return { fetch, expired, changed, user: userEvent.setup() };
}
async function draft(user: ReturnType<typeof userEvent.setup>) {
  await user.clear(await screen.findByLabelText('訂正後の請求額（円）')); await user.type(screen.getByLabelText('訂正後の請求額（円）'), '110000');
  await user.type(screen.getByLabelText('訂正理由'), '契約金額の記載誤り');
}

describe('Invoice correction and immutable history', () => {
  it('retains draft on conflict and requires explicit comparison before using the latest version', async () => {
    const { user, fetch } = setup({ conflict: true }); await draft(user);
    await user.click(screen.getByRole('button', { name: '訂正を保存' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('他の担当者');
    expect(screen.getByRole('button', { name: '訂正を保存' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '最新内容・処理結果を取得' }));
    expect(await screen.findByRole('checkbox')).not.toBeChecked();
    expect(screen.getByLabelText('訂正後の請求額（円）')).toHaveValue('110000'); expect(screen.getByLabelText('訂正理由')).toHaveValue('契約金額の記載誤り');
    expect(screen.getAllByText(/120,000/)).toHaveLength(2);
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: '訂正を保存' }));
    expect(await screen.findByRole('status')).toHaveTextContent('請求を訂正しました');
    const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'PUT');
    expect(writes).toHaveLength(2);
    expect(JSON.parse(String(writes[1][1]?.body))).toEqual({ version: 1, customerId: customer.id, issueDate: invoice.issueDate, dueDate: invoice.dueDate, amount: 110000, reason: '契約金額の記載誤り' });
    expect(screen.queryByLabelText('請求書番号')).not.toBeInTheDocument();
  });
  it('never retries an uncertain mutation before reading the real result and obtaining confirmation', async () => {
    const { user, fetch } = setup({ lost: true }); await draft(user);
    await user.click(screen.getByRole('button', { name: '訂正を保存' }));
    await screen.findByRole('alert'); expect(screen.getByRole('button', { name: '訂正を保存' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '最新内容・処理結果を取得' }));
    await screen.findByRole('checkbox');
    expect(screen.getAllByText(/110,000/)).toHaveLength(2);
    expect(screen.getByRole('button', { name: '訂正を保存' })).toBeDisabled();
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
  });
  it('appends all history pages without hiding the original and forwards cursor zero exactly', async () => {
    const { user, fetch } = setup({ paged: true });
    const table = await screen.findByRole('table', { name: '請求履歴' }); expect(table).toHaveTextContent('登録時の原本');
    await user.click(screen.getByRole('button', { name: '履歴の次の50件' }));
    expect(await within(table).findByText('架空の過去の訂正')).toBeVisible(); expect(table).toHaveTextContent('登録時の原本');
    expect(fetch.mock.calls.some(([path]) => String(path).endsWith('cursor=0&limit=50'))).toBe(true);
    expect(screen.getByRole('button', { name: '履歴の次の50件' })).toBeDisabled();
  });
  it.each([{ status: 'CANCELLED' as const }, { allocated: true }])('blocks corrections for immutable states %j', async (option) => {
    setup(option); expect(await screen.findByRole('button', { name: '訂正を保存' })).toBeDisabled();
  });
  it('keeps a forbidden mutation draft and does not discard the valid session', async () => {
    const { user, expired, fetch } = setup({ denied: true }); await draft(user); await user.click(screen.getByRole('button', { name: '訂正を保存' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('権限'); expect(expired).not.toHaveBeenCalled();
    expect(screen.getByLabelText('訂正理由')).toHaveValue('契約金額の記載誤り'); expect(fetch.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
  });
});
