import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import AllocationHistoryPanel from './AllocationHistoryPanel';
import type { AllocationHistory, Session } from './api';
const session: Session = { userId: 'u1', tenantId: 't1', email: 'actor@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const entry: AllocationHistory = { id: 'op1', requestId: 'request1', kind: 'CONFIRM', reversesId: null, reversedByOperationId: null, paymentVersion: '9007199254740993', actorUserId: 'u1', actorEmail: session.email, reason: '架空入金を照合', occurredAt: '2026-10-30T00:30:00Z', lines: [{ invoiceId: 'i1', invoiceNumber: 'INV1', customerId: 'c1', customerName: '現在の架空顧客', observedInvoiceVersion: '9007199254740992', currentInvoiceVersion: '9007199254740994', cashAmount: 6000, feeAmount: 500 }, { invoiceId: 'i2', invoiceNumber: 'INV2', customerId: 'c2', customerName: '別の架空顧客', observedInvoiceVersion: '0', currentInvoiceVersion: '2', cashAmount: 3000, feeAmount: 0 }] };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
describe('Immutable allocation history and whole-operation reversal', () => {
  it('shows JST actor identity, exact versions and reversal links, and never offers a reversed operation again', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ paymentId: 'p1', expectedPaymentVersion: '9007199254740995', items: [{ ...entry, reversedByOperationId: 'reverse1' }, { ...entry, id: 'reverse1', kind: 'REVERSE', reversesId: entry.id }], nextCursor: '9007199254740996' })));
    render(<AllocationHistoryPanel paymentId="p1" revision="1" session={session} onBusyChange={vi.fn()} onSessionExpired={vi.fn()} onChanged={vi.fn()} />);
    const original = await screen.findByRole('article', { name: '操作 op1' }); expect(original).toHaveTextContent('2026/10/30 09:30:00（日本時間 / JST）'); expect(original).toHaveTextContent('actor@example.test'); expect(original).toHaveTextContent('UUID：u1'); expect(original).toHaveTextContent('9007199254740994'); expect(original).toHaveTextContent('取消済み / 取消操作ID：reverse1'); expect(within(original).queryByRole('button')).not.toBeInTheDocument(); expect(screen.getByRole('article', { name: '操作 reverse1' })).toHaveTextContent('取消対象の元操作ID：op1');
  });
  it('refreshes all current invoice versions then reverses the entire operation, recovering an identical request after a lost response', async () => {
    let posts = 0; let reversed = false;
    const fetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') { posts++; reversed = true; if (posts === 1) throw new TypeError('lost response'); return json({ id: 'reverse1', reversesId: 'op1', requestId: JSON.parse(String(init.body)).requestId }); }
      return json({ paymentId: 'p1', expectedPaymentVersion: '9007199254740995', items: [{ ...entry, reversedByOperationId: reversed ? 'reverse1' : null }], nextCursor: null });
    }); vi.stubGlobal('fetch', fetch); const user = userEvent.setup(); const busy = vi.fn(); const changed = vi.fn(async () => {});
    render(<AllocationHistoryPanel paymentId="p1" revision="1" session={session} onBusyChange={busy} onSessionExpired={vi.fn()} onChanged={changed} />);
    await user.click(await screen.findByRole('button', { name: '操作 op1 の取消内容を再取得' })); const draft = screen.getByRole('region', { name: '操作全体の取消確認' }); expect(draft).toHaveTextContent('INV1'); expect(draft).toHaveTextContent('INV2');
    await user.type(screen.getByLabelText('取消理由'), '\u00a0架空手数料を見直す\u00a0'); await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: '操作全体を取り消す' })); expect(await screen.findByRole('alert')).toHaveTextContent('取消結果が不明'); expect(screen.getByLabelText('取消理由')).toBeDisabled(); expect(busy).toHaveBeenLastCalledWith(true);
    await user.click(screen.getByRole('button', { name: '同じ取消操作IDで結果を確認' })); expect(await screen.findByRole('status')).toHaveTextContent('操作全体を取り消しました');
    const calls = fetch.mock.calls.filter(([, init]) => init?.method === 'POST'); expect(calls).toHaveLength(2); expect(calls[0][1]?.body).toBe(calls[1][1]?.body); expect(JSON.parse(String(calls[0][1]?.body))).toMatchObject({ expectedPaymentVersion: '9007199254740995', reason: '\u00a0架空手数料を見直す\u00a0', invoices: [{ invoiceId: 'i1', expectedInvoiceVersion: '9007199254740994' }, { invoiceId: 'i2', expectedInvoiceVersion: '2' }] }); expect(changed).toHaveBeenCalledOnce(); expect(busy).toHaveBeenLastCalledWith(false);
  });
});
