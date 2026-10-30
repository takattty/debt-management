import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import CsvImportPanel from './CsvImportPanel';
import type { ImportBatch, PaymentImportRow, Session } from './api';
const session: Session = { userId: 'u', tenantId: 't', email: 'review@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const batch = (id: string): ImportBatch => ({ id, kind: 'PAYMENT', status: 'PREVIEW_VALID', version: 0, rowCount: 1, errorCount: 0, confirmedCount: 0, confirmedAt: null, createdAt: '2026-10-30T00:00:00Z', expiresAt: '2099-10-30T00:00:00Z' });
const row = (id: string): PaymentImportRow => ({ recordNumber: 1, rowNumber: 2, transactionId: id, transferDate: '2026-10-30', payerName: '架空', amount: id === 'OLD' ? '1000' : '5000', valid: true });
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
it('keeps a later valid preview when the earlier parallel row read finishes after its error read failed', async () => {
  let releaseOld!: (value: Response) => void;
  const oldRows = new Promise<Response>((resolve) => { releaseOld = resolve; });
  let previews = 0;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === '/api/imports/payments/preview') return json(batch(++previews === 1 ? 'old' : 'new'), 201);
    if (path.includes('/old/payment-rows?')) return oldRows;
    if (path.includes('/old/errors?')) return json({ code: 'INTERNAL_ERROR' }, 500);
    if (path.includes('/new/payment-rows?')) return json({ items: [row('NEW')], nextCursor: 0 });
    if (path.includes('/new/errors?')) return json({ items: [], nextCursor: 0 });
    throw new Error('Unexpected endpoint ' + path);
  }));
  const user = userEvent.setup();
  render(<CsvImportPanel kind="PAYMENT" session={session} onSessionExpired={vi.fn()} onBusyChange={vi.fn()} />);
  const upload = async (name: string) => {
    await user.upload(screen.getByLabelText('入金CSVファイル'), new File(['transaction_id,transfer_date,payer_name,amount\n'], name, { type: 'text/csv' }));
    await user.click(screen.getByRole('button', { name: 'CSVをプレビュー' }));
  };
  await upload('old.csv'); await screen.findByRole('alert');
  await upload('new.csv');
  const table = await screen.findByRole('table', { name: '入金CSVプレビュー' });
  await within(table).findByText('NEW');
  expect(screen.getByRole('button', { name: '全1件を確定して登録' })).toBeEnabled();
  await act(async () => { releaseOld(json({ items: [row('OLD')], nextCursor: 0 })); await oldRows; });
  expect(table).toHaveTextContent('NEW');
  expect(table).not.toHaveTextContent('OLD');
});

it('keeps the current page after recovering the same batch while an older read is still pending', async () => {
  let releaseOld!: (value: Response) => void;
  const oldRows = new Promise<Response>((resolve) => { releaseOld = resolve; });
  let rowReads = 0; let errorReads = 0;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === '/api/imports/payments/preview') return json({ ...batch('same'), rowCount: 51 }, 201);
    if (path === '/api/imports/same') return json({ ...batch('same'), rowCount: 51 });
    if (path.includes('/same/errors?')) return ++errorReads === 1 ? json({ code: 'INTERNAL_ERROR' }, 500) : json({ items: [], nextCursor: 0 });
    if (path.includes('/same/payment-rows?')) {
      if (++rowReads === 1) return oldRows;
      if (new URL(path, 'http://fiction.test').searchParams.get('cursor') === '50') return json({ items: [{ ...row('CURRENT-PAGE-2'), recordNumber: 51, rowNumber: 52 }], nextCursor: 0 });
      return json({ items: [row('CURRENT-PAGE-1')], nextCursor: 50 });
    }
    throw new Error('Unexpected endpoint ' + path);
  }));
  const user = userEvent.setup();
  render(<CsvImportPanel kind="PAYMENT" session={session} onSessionExpired={vi.fn()} onBusyChange={vi.fn()} />);
  await user.upload(screen.getByLabelText('入金CSVファイル'), new File(['transaction_id,transfer_date,payer_name,amount\n'], 'same.csv', { type: 'text/csv' }));
  await user.click(screen.getByRole('button', { name: 'CSVをプレビュー' })); await screen.findByRole('alert');
  await user.click(screen.getByRole('button', { name: 'プレビューを再取得' }));
  const table = await screen.findByRole('table', { name: '入金CSVプレビュー' }); await within(table).findByText('CURRENT-PAGE-1');
  await user.click(screen.getByRole('button', { name: '次の50行' })); await within(table).findByText('CURRENT-PAGE-2');
  await act(async () => { releaseOld(json({ items: [row('STALE-PAGE-1')], nextCursor: 50 })); await oldRows; });
  expect(table).toHaveTextContent('CURRENT-PAGE-2'); expect(table).not.toHaveTextContent('STALE-PAGE-1');
  expect(screen.getByRole('button', { name: '次の50行' })).toBeDisabled();
});

it.each(['CUSTOMER', 'INVOICE'] as const)('preserves the pre-existing %s preview behavior after a parallel read failure', async (kind) => {
  let releaseOld!: (value: Response) => void;
  const oldRows = new Promise<Response>((resolve) => { releaseOld = resolve; });
  let previews = 0;
  const data = (id: string) => ({ recordNumber: 1, rowNumber: 2, customerCode: id, customerName: '架空' + id, invoiceNumber: id, issueDate: '2026-10-01', dueDate: '2026-10-30', amount: '1000', valid: true });
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/preview')) return json({ ...batch(++previews === 1 ? 'compat-old' : 'compat-new'), kind }, 201);
    if (path.includes('/compat-old/rows?')) return oldRows;
    if (path.includes('/compat-old/errors?')) return json({ code: 'INTERNAL_ERROR' }, 500);
    if (path.includes('/compat-new/rows?')) return json({ items: [data('NEW')], nextCursor: 0 });
    if (path.includes('/compat-new/errors?')) return json({ items: [], nextCursor: 0 });
    throw new Error('Unexpected endpoint ' + path);
  }));
  const user = userEvent.setup(); const label = kind === 'CUSTOMER' ? '顧客' : '請求';
  render(<CsvImportPanel kind={kind} session={session} onSessionExpired={vi.fn()} onBusyChange={vi.fn()} />);
  const upload = async (name: string) => {
    await user.upload(screen.getByLabelText(label + 'CSVファイル'), new File(['fictional'], name, { type: 'text/csv' }));
    await user.click(screen.getByRole('button', { name: 'CSVをプレビュー' }));
  };
  await upload('old.csv'); await screen.findByRole('alert'); await upload('new.csv');
  const table = await screen.findByRole('table', { name: label + 'CSVプレビュー' }); await within(table).findAllByText('NEW');
  await act(async () => { releaseOld(json({ items: [data('OLD')], nextCursor: 0 })); await oldRows; });
  expect(table).toHaveTextContent('NEW'); expect(table).not.toHaveTextContent('OLD');
});

const deferredResponse = () => {
  let resolve!: (response: Response) => void;
  const promise = new Promise<Response>((done) => { resolve = done; });
  return { promise, resolve };
};
async function uploadPayment(user: ReturnType<typeof userEvent.setup>, name = 'payment.csv') {
  await user.upload(screen.getByLabelText('入金CSVファイル'), new File(['fictional'], name, { type: 'text/csv' }));
  await user.click(screen.getByRole('button', { name: 'CSVをプレビュー' }));
}

it('publishes no partial batch while new rows are pending and confirms only that new snapshot', async () => {
  const old = deferredResponse(); const fresh = deferredResponse(); let previews = 0;
  const fetch = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/preview')) return json(batch(++previews === 1 ? 'old' : 'new'), 201);
    if (path.includes('/old/payment-rows?')) return old.promise;
    if (path.includes('/old/errors?')) return json({ code: 'INTERNAL_ERROR' }, 500);
    if (path.includes('/new/payment-rows?')) return fresh.promise;
    if (path.includes('/new/errors?')) return json({ items: [], nextCursor: 0 });
    if (path === '/api/imports/new/confirm') return json({ ...batch('new'), status: 'CONFIRMED', version: 1, confirmedCount: 1 });
    throw new Error('Unexpected ' + path);
  }); vi.stubGlobal('fetch', fetch);
  const busy = vi.fn(); const user = userEvent.setup();
  render(<CsvImportPanel kind="PAYMENT" session={session} onSessionExpired={vi.fn()} onBusyChange={busy} />);
  await uploadPayment(user, 'old.csv'); await screen.findByRole('alert'); await uploadPayment(user, 'new.csv');
  const calls = busy.mock.calls.length; expect(busy).toHaveBeenLastCalledWith(true);
  await act(async () => { old.resolve(json({ items: [row('OLD')], nextCursor: 50 })); await old.promise; });
  expect(screen.queryByRole('table')).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: '全1件を確定して登録' })).not.toBeInTheDocument(); expect(busy.mock.calls).toHaveLength(calls);
  await act(async () => { fresh.resolve(json({ items: [row('NEW')], nextCursor: 0 })); await fresh.promise; });
  await screen.findByRole('table', { name: '入金CSVプレビュー' }); await user.click(screen.getByRole('button', { name: '全1件を確定して登録' }));
  const submitted = fetch.mock.calls.filter(([path]) => String(path).endsWith('/confirm')); expect(submitted.map(([path]) => path)).toEqual(['/api/imports/new/confirm']);
});

it('ignores late failures and busy/session callbacks after unmount', async () => {
  const pending = deferredResponse();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/preview') ? json(batch('old'), 201) : String(input).includes('/payment-rows?') ? pending.promise : json({ items: [], nextCursor: 0 })));
  const busy = vi.fn(); const expired = vi.fn(); const user = userEvent.setup();
  const view = render(<CsvImportPanel kind="PAYMENT" session={session} onSessionExpired={expired} onBusyChange={busy} />);
  await uploadPayment(user); view.unmount(); const calls = busy.mock.calls.length;
  await act(async () => { pending.resolve(json({ code: 'UNAUTHENTICATED' }, 401)); await pending.promise; });
  expect(expired).not.toHaveBeenCalled(); expect(busy).toHaveBeenLastCalledWith(false); expect(busy.mock.calls).toHaveLength(calls);
});

it.each(['rows', 'errors'] as const)('keeps a new session snapshot and lock when an old %s page returns', async (target) => {
  const oldPage = deferredResponse(); const freshRows = deferredResponse(); let previews = 0;
  const issue = { errorNumber: 1, rowNumber: 2, field: 'amount', inputValue: '0', inputTruncated: false, reasonCode: 'INVALID_AMOUNT', reason: '架空エラー', correction: '修正してください' };
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith('/preview')) return json(++previews === 1 ? { ...batch('old'), status: 'PREVIEW_INVALID', rowCount: 51, errorCount: 51 } : batch('new'), 201);
    const cursor = new URL(path, 'http://fiction.test').searchParams.get('cursor');
    if (path.includes('/old/payment-rows?')) return cursor === '50' && target === 'rows' ? oldPage.promise : json({ items: [row('OLD-PAGE-1')], nextCursor: 50 });
    if (path.includes('/old/errors?')) return cursor === '50' && target === 'errors' ? oldPage.promise : json({ items: [issue], nextCursor: 50 });
    if (path.includes('/new/payment-rows?')) return freshRows.promise;
    if (path.includes('/new/errors?')) return json({ items: [], nextCursor: 0 });
    throw new Error('Unexpected ' + path);
  }));
  const busy = vi.fn(); const expired = vi.fn(); const user = userEvent.setup();
  const view = render(<CsvImportPanel kind="PAYMENT" session={session} onSessionExpired={expired} onBusyChange={busy} />);
  await uploadPayment(user); await screen.findByRole('table', { name: '入金CSVプレビュー' }); await user.click(screen.getByRole('button', { name: target === 'rows' ? '次の50行' : '次のエラー50件' }));
  view.rerender(<CsvImportPanel kind="PAYMENT" session={{ ...session, tenantId: 'other-tenant', csrfToken: 'fresh' }} onSessionExpired={expired} onBusyChange={busy} />);
  expect(screen.getByLabelText('入金CSVファイル')).toHaveValue(''); await uploadPayment(user, 'new.csv'); const calls = busy.mock.calls.length;
  await act(async () => { oldPage.resolve(target === 'rows' ? json({ items: [row('STALE-PAGE-2')], nextCursor: 99 }) : json({ code: 'CSRF_INVALID' }, 403)); await oldPage.promise; });
  expect(expired).not.toHaveBeenCalled(); expect(busy).toHaveBeenLastCalledWith(true); expect(busy.mock.calls).toHaveLength(calls); expect(screen.queryByRole('table')).not.toBeInTheDocument();
  await act(async () => { freshRows.resolve(json({ items: [row('NEW')], nextCursor: 0 })); await freshRows.promise; });
  const table = await screen.findByRole('table', { name: '入金CSVプレビュー' }); expect(table).toHaveTextContent('NEW'); expect(table).not.toHaveTextContent('STALE'); expect(screen.getByRole('button', { name: '次の50行' })).toBeDisabled();
});

it('clears a current invalid session before its other parallel read returns', async () => {
  const pending = deferredResponse();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/preview') ? json(batch('old'), 201) : String(input).includes('/payment-rows?') ? pending.promise : json({ code: 'UNAUTHENTICATED' }, 401)));
  const expired = vi.fn(); const user = userEvent.setup();
  render(<CsvImportPanel kind="PAYMENT" session={session} onSessionExpired={expired} onBusyChange={vi.fn()} />);
  await uploadPayment(user); await vi.waitFor(() => expect(expired).toHaveBeenCalledOnce()); expect(screen.getByLabelText('入金CSVファイル')).toHaveValue('');
  await act(async () => { pending.resolve(json({ items: [row('OLD')], nextCursor: 50 })); await pending.promise; });
  expect(screen.queryByRole('table')).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: '全1件を確定して登録' })).not.toBeInTheDocument(); expect(expired).toHaveBeenCalledOnce();
});
