import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import AllocationConfirmation from './AllocationConfirmation';
import type { PaymentCandidate, PaymentCandidates, Session } from './api';
const session: Session = { userId: 'u1', tenantId: 't1', email: 'actor@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const invoice: PaymentCandidate = { invoiceId: 'i1', customerId: 'c1', customerCode: 'C1', customerName: '架空顧客', invoiceNumber: 'INV1', issueDate: '2026-10-01', dueDate: '2026-11-01', amount: 10000, outstandingAmount: 9000, expectedInvoiceVersion: '9007199254740993', manualPayerMatch: false, exactAmountMatch: false };
const candidates: PaymentCandidates = { paymentId: 'p1', payerName: '架空振込人', transferDate: '2026-10-30', unallocatedAmount: 8500, expectedPaymentVersion: '9007199254740994', payerMatchStatus: 'UNREGISTERED', matchedCustomerCount: 0, cashCandidateStatus: 'AVAILABLE', requiresHumanConfirmation: true, items: [invoice], nextCursor: null };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
async function setup(options: { lost?: boolean; conflict?: boolean; numericVersion?: boolean } = {}) {
  let posts = 0;
  const fetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method !== 'POST') return json(options.numericVersion ? { ...candidates, expectedPaymentVersion: 9007199254740994 } : candidates);
    posts++;
    if (options.conflict) return json({ code: 'VERSION_CONFLICT' }, 409);
    if (options.lost && posts === 1) throw new TypeError('response lost');
    return json({ id: 'op1', requestId: JSON.parse(String(init.body)).requestId, kind: 'CONFIRM' });
  }); vi.stubGlobal('fetch', fetch);
  const busy = vi.fn(); const complete = vi.fn(async () => {}); const user = userEvent.setup();
  render(<AllocationConfirmation paymentId="p1" selected={[invoice]} session={session} onBusyChange={busy} onSessionExpired={vi.fn()} onConfirmed={complete} />);
  await user.click(screen.getByRole('button', { name: '選択内容を再取得して確認' })); if (options.numericVersion) await screen.findByRole('alert'); else await screen.findByLabelText('現金（円） INV1');
  return { user, fetch, busy, complete };
}
async function enter(user: ReturnType<typeof userEvent.setup>, cash = '8500', fee = '500') {
  await user.type(screen.getByLabelText('現金（円） INV1'), cash); await user.clear(screen.getByLabelText('手数料（円） INV1')); await user.type(screen.getByLabelText('手数料（円） INV1'), fee); await user.type(screen.getByLabelText('消込理由'), '架空明細を照合'); await user.click(screen.getByRole('checkbox'));
}
describe('Explicit allocation confirmation', () => {
  it('refuses a numeric version instead of silently rounding or sending a transport value from another contract', async () => {
    const { fetch } = await setup({ numericVersion: true }); expect(screen.getByRole('alert')).toHaveTextContent('versionを確認できません'); expect(screen.queryByLabelText('現金（円） INV1')).not.toBeInTheDocument(); expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
  });
  it('retains exact string versions and the identical request body after a lost response, locking inputs until the result is known', async () => {
    const { user, fetch, busy, complete } = await setup({ lost: true }); await enter(user);
    await user.click(screen.getByRole('button', { name: 'この内容で消込を確定' })); expect(await screen.findByRole('alert')).toHaveTextContent('確定結果が不明');
    expect(screen.getByLabelText('現金（円） INV1')).toBeDisabled(); expect(busy).toHaveBeenLastCalledWith(true); expect(complete).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: '同じ操作IDで結果を確認' })); expect(await screen.findByRole('status')).toHaveTextContent('消込を確定しました');
    const posts = fetch.mock.calls.filter(([, init]) => init?.method === 'POST'); expect(posts).toHaveLength(2); expect(posts[0][1]?.body).toBe(posts[1][1]?.body);
    expect(JSON.parse(String(posts[0][1]?.body))).toMatchObject({ expectedPaymentVersion: '9007199254740994', reason: '架空明細を照合', lines: [{ invoiceId: 'i1', expectedInvoiceVersion: '9007199254740993', cashAmount: 8500, feeAmount: 500 }] });
    expect(complete).toHaveBeenCalledOnce(); expect(busy).toHaveBeenLastCalledWith(false);
  });
  it.each([['9007199254740992', '0'], ['1.5', '0'], ['0', '100'], ['8500', '501']])('rejects invalid cash=%s fee=%s without writing', async (cash, fee) => {
    const { user, fetch } = await setup(); await enter(user, cash, fee); await user.click(screen.getByRole('button', { name: 'この内容で消込を確定' })); expect(await screen.findByRole('alert')).toBeVisible(); expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
  });
  it('blocks a stale operation until the user explicitly refreshes and reviews new inputs', async () => {
    const { user, fetch } = await setup({ conflict: true }); await enter(user); await user.click(screen.getByRole('button', { name: 'この内容で消込を確定' })); expect(await screen.findByRole('alert')).toHaveTextContent('他の担当者'); expect(screen.getByRole('button', { name: 'この内容で消込を確定' })).toBeDisabled(); expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: '選択内容を再取得して確認' })); expect(await screen.findByLabelText('現金（円） INV1')).toHaveValue(''); expect(screen.getByRole('checkbox')).not.toBeChecked();
  });
});
