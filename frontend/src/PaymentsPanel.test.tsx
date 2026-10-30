import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import type { ImportBatch, Payment, PaymentImportRow, Session } from './api';

const session: Session = { userId: 'actor-a', tenantId: 'tenant-a', email: 'accountant@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const payment: Payment = { id: 'payment-a', transactionId: 'TXN-001', transferDate: '0001-01-01', payerName: '架空 ｶｸｳ é é', amount: 9007199254740991, unallocatedAmount: 9007199254740991, sourceBatchId: 'original-batch', createdByUserId: session.userId, createdAt: '2026-10-30T00:00:00Z' };
const row: PaymentImportRow = { recordNumber: 1, rowNumber: 2, transactionId: payment.transactionId, transferDate: payment.transferDate, payerName: payment.payerName, amount: String(payment.amount), valid: true };
const batch: ImportBatch = { id: 'batch-a', kind: 'PAYMENT', status: 'PREVIEW_VALID', version: 0, rowCount: 1, errorCount: 0, createdAt: '2026-10-30T00:00:00Z', expiresAt: '2099-10-30T00:00:00Z', confirmedAt: null, confirmedCount: 0 };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
type Options = { paged?: boolean; lostResponse?: boolean; unavailableRecovery?: boolean; duplicate?: boolean; duplicateOnConfirm?: boolean; expired?: boolean; serverExpired?: boolean; requestNotDelivered?: boolean; listingFailure?: { status: number; code: string }; confirmFailure?: { status: number; code: string } };
function mockServer(options: Options = {}) {
  let uploads = 0; let lookups = 0; let current = batch;
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/session') return json(session);
    if (path === '/api/customers') return json([]);
    if (path === '/api/imports/payments/preview') {
      uploads++; current = { ...batch, id: `batch-${uploads}`, ...(options.expired && uploads === 1 ? { expiresAt: '2020-01-01T00:00:00Z' } : {}), ...(options.duplicate ? { originalBatchId: 'original-batch', status: 'PREVIEW_INVALID', errorCount: 1 } : {}) }; return json(current, 201);
    }
    if (path.endsWith('/confirm')) {
      if (options.confirmFailure) return json({ code: options.confirmFailure.code }, options.confirmFailure.status);
      if (options.serverExpired && uploads === 1) return json({ code: 'IMPORT_EXPIRED' }, 409);
      if (options.duplicateOnConfirm) return json({ code: 'IMPORT_ALREADY_CONFIRMED', errors: [{ field: 'batchId', message: 'original-batch' }] }, 409);
      if (options.requestNotDelivered) throw new TypeError('request not delivered');
      current = { ...current, status: 'CONFIRMED', confirmedCount: 1, confirmedAt: '2026-10-30T01:00:00Z', version: 1 };
      if (options.lostResponse) throw new TypeError('response lost');
      return json(current);
    }
    if (path.includes('/payment-rows?')) return json({ items: [row], nextCursor: 0 });
    if (path.includes('/errors?')) return json({ items: [], nextCursor: 0 });
    if (path.startsWith('/api/imports/')) {
      lookups++; if (options.unavailableRecovery && lookups === 1) throw new TypeError('recovery unavailable');
      return json(options.duplicate || options.duplicateOnConfirm ? { ...batch, id: 'original-batch', status: 'CONFIRMED', confirmedCount: 1, confirmedAt: '2026-10-30T01:00:00Z', version: 1 } : current);
    }
    if (path.startsWith('/api/payments?')) {
      if (options.listingFailure) return json({ code: options.listingFailure.code }, options.listingFailure.status);
      const query = new URL(path, 'http://example.test').searchParams;
      return json({ items: [{ ...payment, transactionId: query.has('cursor') ? 'TXN-002' : payment.transactionId }], nextCursor: options.paged && !query.has('cursor') ? `0001-01-01:${payment.id}` : null });
    }
    throw new Error(`Unexpected endpoint: ${path} ${init?.method}`);
  });
  vi.stubGlobal('fetch', fetch); return fetch;
}
async function start(page = '入金CSV取込') {
  const user = userEvent.setup(); render(<App />); await user.click(await screen.findByRole('button', { name: page })); return user;
}
async function upload(user: ReturnType<typeof userEvent.setup>) {
  await user.upload(screen.getByLabelText('入金CSVファイル'), new File(['transaction_id,transfer_date,payer_name,amount\nTXN-001,0001-01-01,架空振込人,100000\n'], 'payments.csv', { type: 'text/csv' }));
  await user.click(screen.getByRole('button', { name: 'CSVをプレビュー' })); await screen.findByRole('table', { name: '入金CSVプレビュー' });
}

describe('Payment confirmation and server-paged immutable facts', () => {
  it('shows exact largest safe yen amounts, source batch and read-only payment facts with a separate human allocation action', async () => {
    mockServer(); await start('入金一覧'); const table = await screen.findByRole('table', { name: '入金一覧' });
    expect(within(table).getAllByText(/9,007,199,254,740,991/)).toHaveLength(2); expect(table).toHaveTextContent(payment.payerName); expect(table).toHaveTextContent('0001-01-01'); expect(table).toHaveTextContent(payment.sourceBatchId);
    expect(screen.getByText(/手数料は入金原本の金額を変更しません/)).toBeVisible(); expect(screen.getByRole('button', { name: `入金 ${payment.transactionId} の消込` })).toBeVisible();
  });
  it('sends the exact server cursor with applied filters even if the next filter draft changes', async () => {
    const fetch = mockServer({ paged: true }); const user = await start('入金一覧'); await screen.findByRole('table', { name: '入金一覧' });
    await user.type(screen.getByLabelText('振込日（開始）'), '0001-01-01'); await user.type(screen.getByLabelText('振込日（終了）'), '2026-10-30'); await user.click(screen.getByRole('button', { name: '条件で表示' }));
    await screen.findByRole('cell', { name: payment.transactionId }); await user.clear(screen.getByLabelText('振込日（終了）')); await user.click(screen.getByRole('button', { name: '次の50件' }));
    expect(await screen.findByRole('cell', { name: 'TXN-002' })).toBeVisible();
    const calls = fetch.mock.calls.filter(([path]) => String(path).startsWith('/api/payments?')); const query = new URL(String(calls.at(-1)?.[0]), 'http://example.test').searchParams;
    expect(query.get('cursor')).toBe(`0001-01-01:${payment.id}`); expect(query.get('dateFrom')).toBe('0001-01-01'); expect(query.get('dateTo')).toBe('2026-10-30'); expect(query.get('limit')).toBe('50');
  });
  it('rejects a reversed date range without issuing another lookup', async () => {
    const fetch = mockServer(); const user = await start('入金一覧'); await screen.findByRole('table', { name: '入金一覧' });
    await user.type(screen.getByLabelText('振込日（開始）'), '2026-11-30'); await user.type(screen.getByLabelText('振込日（終了）'), '2026-10-30'); await user.click(screen.getByRole('button', { name: '条件で表示' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('開始日は終了日以前'); expect(fetch.mock.calls.filter(([path]) => String(path).startsWith('/api/payments?'))).toHaveLength(1);
  });
  it.each([false, true])('recovers a committed import without repeating confirmation, delayed recovery=%s', async (unavailableRecovery) => {
    const fetch = mockServer({ lostResponse: true, unavailableRecovery }); const user = await start(); await upload(user); await user.click(screen.getByRole('button', { name: '全1件を確定して登録' }));
    if (unavailableRecovery) {
      expect(await screen.findByRole('button', { name: '確定結果を確認' })).toBeVisible(); expect(screen.getByRole('button', { name: '全1件を確定して登録' })).toBeDisabled(); expect(screen.getByLabelText('入金CSVファイル')).toBeDisabled();
      await user.click(screen.getByRole('button', { name: '確定結果を確認' }));
    }
    expect(await screen.findByRole('status')).toHaveTextContent('1件の入金を登録しました'); await user.click(screen.getByRole('button', { name: '入金一覧へ' })); expect(await screen.findByRole('table', { name: '入金一覧' })).toHaveTextContent(payment.transactionId);
    const confirmations = fetch.mock.calls.filter(([path]) => String(path).endsWith('/confirm')); expect(confirmations).toHaveLength(1); expect(JSON.parse(String(confirmations[0][1]?.body))).toEqual({ version: 0 });
    expect((confirmations[0][1]?.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe(session.csrfToken);
  });
  it.each(['duplicate', 'duplicateOnConfirm'] as const)('opens the original payment batch after %s without creating a second import', async (option) => {
    const fetch = mockServer({ [option]: true }); const user = await start(); await upload(user);
    if (option === 'duplicateOnConfirm') await user.click(screen.getByRole('button', { name: '全1件を確定して登録' }));
    await user.click(await screen.findByRole('button', { name: '元の取込結果を確認' })); expect(await screen.findByRole('status')).toHaveTextContent('1件の入金を登録しました');
    expect(fetch.mock.calls.some(([path]) => String(path) === '/api/imports/original-batch')).toBe(true); expect(fetch.mock.calls.filter(([path]) => String(path).endsWith('/confirm'))).toHaveLength(option === 'duplicate' ? 0 : 1);
  });
  it.each(['expired', 'serverExpired'] as const)('requires a fresh preview after %s instead of reusing the blocked batch', async (option) => {
    const fetch = mockServer({ [option]: true }); const user = await start(); await upload(user);
    if (option === 'serverExpired') { await user.click(screen.getByRole('button', { name: '全1件を確定して登録' })); await screen.findByRole('alert'); }
    expect(screen.getByRole('button', { name: '全1件を確定して登録' })).toBeDisabled();
    await upload(user); expect(screen.getByRole('button', { name: '全1件を確定して登録' })).toBeEnabled();
    expect(fetch.mock.calls.filter(([path]) => String(path).endsWith('/preview'))).toHaveLength(2);
  });
  it.each([{ status: 401, code: 'UNAUTHENTICATED' }, { status: 403, code: 'ACCESS_DENIED' }])('handles confirmation failure without resubmitting or leaking state %j', async (confirmFailure) => {
    const fetch = mockServer({ confirmFailure }); const user = await start(); await upload(user); await user.click(screen.getByRole('button', { name: '全1件を確定して登録' })); await screen.findByRole('alert');
    if (confirmFailure.status === 401) { expect(screen.queryByRole('table', { name: '入金CSVプレビュー' })).not.toBeInTheDocument(); expect(screen.getByLabelText('パスワード')).toHaveValue(''); }
    else expect(screen.getByText(session.email)).toBeVisible(); expect(fetch.mock.calls.filter(([path]) => String(path).endsWith('/confirm'))).toHaveLength(1);
  });
  it.each([{ status: 401, code: 'UNAUTHENTICATED' }, { status: 403, code: 'ACCESS_DENIED' }])('distinguishes list session expiry and role refusal %j', async (listingFailure) => {
    mockServer({ listingFailure }); await start('入金一覧'); expect(await screen.findByRole('alert')).toBeVisible();
    if (listingFailure.status === 401) expect(screen.getByLabelText('パスワード')).toHaveValue(''); else expect(screen.getByText(session.email)).toBeVisible(); expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});
