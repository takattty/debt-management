import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import PaymentAllocationPanel from './PaymentAllocationPanel';
import { ApiError, type Payment, type PaymentCandidate, type PaymentCandidates, type Session } from './api';
const session: Session = { userId: 'u1', tenantId: 't1', email: 'actor@example.test', role: 'ACCOUNTANT', csrfHeaderName: 'X-CSRF-TOKEN', csrfToken: 'csrf' };
const payment: Payment = { id: 'p1', transactionId: 'TX1', payerName: '架空振込人', transferDate: '2026-10-30', amount: 10000, unallocatedAmount: 9000, sourceBatchId: 'b1', createdByUserId: 'u1', createdAt: '2026-10-30T00:00:00Z' };
const candidate: PaymentCandidate = { invoiceId: 'i1', customerId: 'c1', customerCode: 'C1', customerName: '架空顧客', invoiceNumber: 'INV1', issueDate: '2026-10-01', dueDate: '2027-01-01', amount: 10000, outstandingAmount: 9000, expectedInvoiceVersion: '9007199254740993', manualPayerMatch: true, exactAmountMatch: true };
const page: PaymentCandidates = { paymentId: payment.id, payerName: payment.payerName, transferDate: payment.transferDate, unallocatedAmount: 9000, expectedPaymentVersion: '9007199254740993', payerMatchStatus: 'MULTIPLE_CUSTOMERS', matchedCustomerCount: 2, cashCandidateStatus: 'AVAILABLE', requiresHumanConfirmation: true, items: [candidate], nextCursor: 'opaque:cursor' };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
describe('Human payment allocation selection', () => {
  it('never preselects even exact matches and keeps selections across server cursors including another customer', async () => {
    const fetch = vi.fn(async (input: RequestInfo | URL) => String(input).includes('/allocations?') ? json({ paymentId: payment.id, expectedPaymentVersion: page.expectedPaymentVersion, items: [], nextCursor: null }) : json(String(input).includes('cursor=') ? { ...page, nextCursor: null, items: [{ ...candidate, invoiceId: 'i2', invoiceNumber: 'INV2', customerId: 'c2', customerCode: 'C2', manualPayerMatch: false, exactAmountMatch: false }] } : page)); vi.stubGlobal('fetch', fetch);
    const user = userEvent.setup(); render(<PaymentAllocationPanel payment={payment} session={session} onChanged={vi.fn()} onBusyChange={vi.fn()} onSessionExpired={vi.fn()} onClose={vi.fn()} />);
    const first = await screen.findByRole('checkbox', { name: '請求 INV1 を選択' }); expect(first).not.toBeChecked(); expect(screen.getByText(/2顧客に対応/)).toBeVisible();
    await user.click(first); expect(screen.getByRole('region', { name: '選択した請求' })).toHaveTextContent('9007199254740993');
    await user.click(screen.getByRole('button', { name: '次の候補50件' })); await user.click(await screen.findByRole('checkbox', { name: '請求 INV2 を選択' }));
    expect(screen.getByRole('region', { name: '選択した請求' })).toHaveTextContent('INV1'); expect(screen.getByRole('region', { name: '選択した請求' })).toHaveTextContent('INV2');
    const reads = fetch.mock.calls.filter(([path]) => String(path).includes('/candidates?')); expect(String(reads[1][0])).toContain('cursor=opaque%3Acursor'); expect(reads).toHaveLength(2);
    await user.click(screen.getByRole('button', { name: '最新候補を取得して選び直す' })); await screen.findByRole('checkbox', { name: '請求 INV1 を選択' }); expect(screen.queryByRole('region', { name: '選択した請求' })).not.toBeInTheDocument();
  });
  it.each([{ status: 401, code: 'UNAUTHENTICATED' }, { status: 403, code: 'CSRF_INVALID' }, { status: 403, code: 'ACCESS_DENIED' }])('handles scoped read refusal %j', async (failure) => {
    vi.stubGlobal('fetch', vi.fn(async () => json(failure, failure.status))); const expired = vi.fn(); render(<PaymentAllocationPanel payment={payment} session={session} onChanged={vi.fn()} onBusyChange={vi.fn()} onSessionExpired={expired} onClose={vi.fn()} />);
    if (failure.code === 'ACCESS_DENIED') { expect(await screen.findByRole('alert')).toHaveTextContent('権限'); expect(expired).not.toHaveBeenCalled(); }
    else { await vi.waitFor(() => expect(expired).toHaveBeenCalledWith(expect.any(ApiError))); }
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});
