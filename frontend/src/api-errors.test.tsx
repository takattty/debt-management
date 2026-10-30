import { describe, expect, it, vi } from 'vitest';
import { api, ApiError, type Session } from './api';
import { conflictGuidance } from './conflict-guidance';
const session: Session = { userId: 'u', tenantId: 't', email: 'actor@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const customer = { code: 'C1', name: '架空顧客' };
const operations = {
  create: () => api.createCustomer(session, customer),
  update: () => api.updateCustomer(session, 'customer', { ...customer, version: 0 }),
  list: () => api.listCustomers(),
  invoice: () => api.getInvoice('invoice'),
  receivables: () => api.listReceivables(),
};
async function errorFor(operation: () => Promise<unknown>, code?: string) {
  const fetch = vi.fn(async () => new Response(JSON.stringify(code ? { code } : {}), { status: 409 })); vi.stubGlobal('fetch', fetch);
  const error = await operation().catch((value: unknown) => value); expect(error).toBeInstanceOf(ApiError); expect(fetch).toHaveBeenCalledOnce(); return error as ApiError;
}
describe('Conflict guidance is scoped to the actual error and operation', () => {
  it.each([
    ['VERSION_CONFLICT', '他の担当者が残高を更新'], ['BALANCE_CONFLICT', '残高が不足'],
    ['INVOICE_NOT_ACTIVE', '請求は取消済み'], ['ALLOCATION_ALREADY_REVERSED', '消込はすでに取消済み'],
    ['IDEMPOTENCY_CONFLICT', '同じ操作IDに別の内容'], ['VERSION_EXHAUSTED', 'versionの上限'],
  ])('recognizes real AllocationCommands %s without future transport DTOs', (code, expected) => {
    const path = code === 'ALLOCATION_ALREADY_REVERSED' ? '/api/payments/{paymentId}/allocations/{allocationId}/reversal' : '/api/payments/{paymentId}/allocations';
    const message = conflictGuidance(path, 'POST', code); expect(message).toContain(expected); expect(message).not.toContain('顧客コード');
    if (code === 'BALANCE_CONFLICT') expect(message).toContain('最新内容を再取得');
    if (code === 'ALLOCATION_ALREADY_REVERSED') expect(message).toContain('履歴を再取得');
  });
  it.each(['FUTURE_CONFLICT', 'constructor', 'toString', '__proto__', undefined])('uses safe allocation fallback for %s', (code) => {
    expect(conflictGuidance('/api/payments/{paymentId}/allocations', 'POST', code)).toBe('現在の状態では処理できません。最新内容を取得して確認してください。');
  });
  it.each(Object.keys(operations) as (keyof typeof operations)[])('does not label an unknown %s conflict as customer duplication', async (kind) => {
    const error = await errorFor(operations[kind], 'FUTURE_CONFLICT'); expect(error.code).toBe('FUTURE_CONFLICT'); expect(error.message).toBe('現在の状態では処理できません。最新内容を取得して確認してください。');
  });
  it('handles a missing code with safe guidance', async () => {
    expect((await errorFor(operations.create)).message).toContain('最新内容を取得');
  });
  it.each(['create', 'update'] as const)('preserves explicit customer duplication for %s', async (kind) => {
    expect((await errorFor(operations[kind], 'DUPLICATE_CUSTOMER_CODE')).message).toBe('この顧客コードは登録済みです。別のコードを入力してください。');
  });
  it.each(['list', 'invoice', 'receivables'] as const)('does not mislabel %s when another domain code is returned', async (kind) => {
    expect((await errorFor(operations[kind], 'DUPLICATE_CUSTOMER_CODE')).message).not.toContain('顧客コード');
  });
  it.each(['constructor', 'toString', '__proto__'])('uses safe HTTP invoice guidance for inherited key %s', async (code) => {
    const error = await errorFor(operations.invoice, code); expect(error.code).toBe(code); expect(error.message).toBe('現在の状態では処理できません。最新内容を取得して確認してください。');
  });
  it.each(['constructor', 'toString', '__proto__'])('uses safe HTTP import guidance for inherited key %s', async (code) => {
    const error = await errorFor(() => api.confirmImport(session, 'batch', { version: 0 }), code); expect(error.code).toBe(code); expect(error.message).toBe('取込内容が変わっています。状態を確認して、CSVをもう一度プレビューしてください。');
  });
  it.each([['/api/customers', 'DELETE'], ['/api/customers/{id}', 'GET'], ['/api/customers-archive', 'POST']])('requires the correct customer mutation %s %s', (path, method) => {
    expect(conflictGuidance(path, method, 'DUPLICATE_CUSTOMER_CODE')).not.toContain('顧客コード');
  });
});
