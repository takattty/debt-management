import { describe, expect, it, vi } from 'vitest';
import { api, ApiError, type AllocationConfirmation, type AllocationReversal, type Session } from './api';

const session: Session = { userId: 'user', tenantId: 'tenant', email: 'actor@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const confirmation: AllocationConfirmation = { requestId: 'request', expectedPaymentVersion: '0', reason: '架空明細を照合', lines: [{ invoiceId: 'invoice', expectedInvoiceVersion: '0', cashAmount: 100, feeAmount: 0 }] };
const reversal: AllocationReversal = { requestId: 'request', expectedPaymentVersion: '1', reason: '架空明細を再確認', invoices: [{ invoiceId: 'invoice', expectedInvoiceVersion: '1' }] };
function reject(code?: string) {
  const fetch = vi.fn(async () => new Response(JSON.stringify(code ? { code } : {}), { status: 409 })); vi.stubGlobal('fetch', fetch); return fetch;
}
async function failure(operation: () => Promise<unknown>) {
  const result = await operation().catch((error: unknown) => error); expect(result).toBeInstanceOf(ApiError); return result as ApiError;
}

describe('409 guidance follows actual domain error codes', () => {
  // AllocationCommands emits these strings and AllocationErrors returns them as ApiError.code.
  it.each([
    ['VERSION_CONFLICT', '他の担当者が残高を更新'],
    ['BALANCE_CONFLICT', '現金または請求の残高が不足'],
    ['INVOICE_NOT_ACTIVE', '選択した請求は取消済み'],
    ['ALLOCATION_ALREADY_REVERSED', 'この消込はすでに取消済み'],
    ['IDEMPOTENCY_CONFLICT', '同じ操作IDに別の内容'],
    ['VERSION_EXHAUSTED', 'versionの上限'],
  ])('shows allocation guidance for real backend %s', async (code, expected) => {
    const fetch = reject(code); const error = await failure(() => code === 'ALLOCATION_ALREADY_REVERSED' ? api.reverseAllocation(session, 'payment', 'operation', reversal) : api.confirmAllocation(session, 'payment', confirmation));
    expect(error.code).toBe(code); expect(error.message).toContain(expected); expect(error.message).not.toContain('顧客コード'); expect(fetch).toHaveBeenCalledOnce();
    if (code === 'BALANCE_CONFLICT') expect(error.message).toContain('最新内容を再取得');
    if (code === 'ALLOCATION_ALREADY_REVERSED') expect(error.message).toContain('履歴を再取得');
  });
  it.each(['confirm', 'reverse', 'invoice', 'customer', 'payments', 'receivables'] as const)('uses a safe unknown-code fallback for %s', async (kind) => {
    reject('FUTURE_CONFLICT');
    const operations = {
      confirm: () => api.confirmAllocation(session, 'payment', confirmation),
      reverse: () => api.reverseAllocation(session, 'payment', 'operation', reversal),
      invoice: () => api.getInvoice('invoice'),
      customer: () => api.createCustomer(session, { code: 'C1', name: '架空顧客' }),
      payments: () => api.listPayments(),
      receivables: () => api.listReceivables(),
    };
    const error = await failure(operations[kind]); expect(error.message).toBe('現在の状態では処理できません。最新内容を取得して確認してください。'); expect(error.message).not.toContain('顧客コード');
  });
  it.each([undefined, 'DUPLICATE_CUSTOMER_CODE'])('does not label an allocation as customer duplication when code=%s', async (code) => {
    reject(code); const error = await failure(() => api.confirmAllocation(session, 'payment', confirmation)); expect(error.message).not.toContain('顧客コード'); expect(error.message).toContain('最新内容を取得');
  });
  it.each(['create', 'update'] as const)('preserves explicit customer duplicate recovery for %s', async (kind) => {
    reject('DUPLICATE_CUSTOMER_CODE'); const error = await failure(() => kind === 'create' ? api.createCustomer(session, { code: 'C1', name: '架空顧客' }) : api.updateCustomer(session, 'customer', { code: 'C1', name: '架空顧客', version: 0 }));
    expect(error.message).toBe('この顧客コードは登録済みです。別のコードを入力してください。');
  });
});
