import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import type { Customer, ImportBatch, Receivable, Session } from './api';

const customer: Customer = { id: '11111111-1111-1111-1111-111111111180', code: 'C001', name: '架空商事', version: 0 };
const session: Session = { userId: '11111111-1111-1111-1111-111111111112', tenantId: '11111111-1111-1111-1111-111111111111', email: 'alpha-accountant@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const invoice: Receivable = { id: '11111111-1111-1111-1111-111111111181', customerId: customer.id, customerCode: customer.code, customerName: customer.name, invoiceNumber: 'INV-001', issueDate: '2026-10-30', dueDate: '2026-11-30', amount: 9007199254740991, outstandingAmount: 9007199254740991, version: 0, status: 'ACTIVE' };
const cursor = `2026-11-30:${invoice.id}`;
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });

function mockServer(options: { paginated?: boolean; listingFailure?: { status: number; code: string }; referenceConflict?: boolean; lostResponse?: boolean } = {}) {
  let uploadNumber = 0;
  let invalid = false;
  let committed = false;
  let preview: ImportBatch;
  const fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === '/api/session') return json(session);
    if (path === '/api/customers') return json([customer]);
    if (path.startsWith('/api/receivables?')) {
      if (options.listingFailure) return json({ code: options.listingFailure.code }, options.listingFailure.status);
      const query = new URL(path, 'http://example.test').searchParams;
      return json({ items: [{ ...invoice, invoiceNumber: query.has('cursor') ? 'INV-002' : 'INV-001' }], nextCursor: options.paginated && !query.has('cursor') ? cursor : null });
    }
    if (path === '/api/imports/invoices/preview') {
      uploadNumber++; invalid = false;
      preview = { id: `11111111-1111-1111-1111-11111111119${uploadNumber}`, kind: 'INVOICE', status: 'PREVIEW_VALID', version: 0, rowCount: 1, errorCount: 0, createdAt: '2026-10-30T00:00:00Z', expiresAt: '2099-10-30T00:00:00Z', confirmedAt: null, confirmedCount: 0 };
      return json(preview, 201);
    }
    if (path.endsWith('/confirm')) {
      if (options.referenceConflict && uploadNumber === 1) { invalid = true; return json({ code: 'IMPORT_REFERENCE_CONFLICT' }, 409); }
      committed = true;
      if (options.lostResponse) throw new TypeError('Response lost');
      return json({ ...preview, status: 'CONFIRMED', version: 1, confirmedCount: 1, confirmedAt: '2026-10-30T01:00:00Z' });
    }
    if (path.includes('/rows?')) return json({ items: [{ recordNumber: 1, rowNumber: 2, customerCode: customer.code, customerName: customer.name, invoiceNumber: invoice.invoiceNumber, issueDate: invoice.issueDate, dueDate: invoice.dueDate, amount: '100000', valid: !invalid }], nextCursor: 0 });
    if (path.includes('/errors?')) return json({ items: invalid ? [{ errorNumber: 1, rowNumber: 2, field: 'customer_code', inputValue: customer.code, inputTruncated: false, reasonCode: 'CUSTOMER_CHANGED', reason: '顧客がプレビュー後に更新されています。', correction: '変更後の顧客情報を確認して再プレビューしてください。' }] : [], nextCursor: 0 });
    if (path.startsWith('/api/imports/')) return json({ ...preview, ...(invalid ? { status: 'PREVIEW_INVALID', errorCount: 1, version: 1 } : committed ? { status: 'CONFIRMED', confirmedCount: 1, confirmedAt: '2026-10-30T01:00:00Z', version: 1 } : {}) });
    throw new Error(`Unexpected endpoint: ${path}`);
  });
  vi.stubGlobal('fetch', fetch); return fetch;
}

async function start(page = '債権一覧') {
  const user = userEvent.setup(); render(<App />);
  await user.click(await screen.findByRole('button', { name: page })); return user;
}

async function upload(user: ReturnType<typeof userEvent.setup>) {
  await user.upload(screen.getByLabelText('請求CSVファイル'), new File(['customer_code,invoice_number,issue_date,due_date,amount\nC001,INV-001,2026-10-30,2026-11-30,100000\n'], 'invoices.csv', { type: 'text/csv' }));
  await user.click(screen.getByRole('button', { name: 'CSVをプレビュー' }));
  await screen.findByRole('table', { name: '請求CSVプレビュー' });
}

describe('Confirmed invoices and server-paged receivables', () => {
  it('formats the largest safe yen integer exactly without calculating totals', async () => {
    mockServer(); await start();
    const table = await screen.findByRole('table', { name: '債権一覧' });
    expect(within(table).getAllByText(/9,007,199,254,740,991/)).toHaveLength(2);
    expect(table).toHaveTextContent('2026-11-30');
    expect(screen.getByRole('button', { name: '次の50件' })).toBeDisabled();
  });

  it('keeps the exact server cursor and applied filters while filter drafts change', async () => {
    const fetch = mockServer({ paginated: true }); const user = await start();
    await screen.findByRole('table', { name: '債権一覧' });
    await user.selectOptions(screen.getByRole('combobox', { name: '顧客で絞り込む' }), customer.id);
    await user.type(screen.getByLabelText('支払期日（終了）'), '2026-11-30');
    await user.click(screen.getByRole('button', { name: '条件で表示' }));
    await screen.findByRole('cell', { name: 'INV-001' });
    await user.clear(screen.getByLabelText('支払期日（終了）'));
    await user.click(screen.getByRole('button', { name: '次の50件' }));
    expect(await screen.findByRole('cell', { name: 'INV-002' })).toBeVisible();
    const queries = fetch.mock.calls.filter(([path]) => String(path).startsWith('/api/receivables?'));
    const final = new URL(queries.at(-1)?.[0], 'http://example.test').searchParams;
    expect(final.get('cursor')).toBe(cursor); expect(final.get('customerId')).toBe(customer.id); expect(final.get('dueTo')).toBe('2026-11-30'); expect(final.get('limit')).toBe('50');
    expect(screen.queryByRole('cell', { name: 'INV-001' })).not.toBeInTheDocument();
  });

  it('recovers the original confirmed invoice after a lost response and rereads receivables from the result action', async () => {
    const fetch = mockServer({ lostResponse: true }); const user = await start('請求CSV取込'); await upload(user);
    await user.click(screen.getByRole('button', { name: '全1件を確定して登録' }));
    expect(await screen.findByRole('status')).toHaveTextContent('1件の請求を登録しました');
    await user.click(screen.getByRole('button', { name: '債権一覧へ' }));
    expect(await screen.findByRole('table', { name: '債権一覧' })).toHaveTextContent('INV-001');
    const confirmations = fetch.mock.calls.filter(([path]) => String(path).endsWith('/confirm'));
    expect(confirmations).toHaveLength(1); expect(JSON.parse(confirmations[0][1].body)).toEqual({ version: 0 });
  });

  it('shows server-saved row corrections after a reference conflict and requires a new preview', async () => {
    const fetch = mockServer({ referenceConflict: true }); const user = await start('請求CSV取込'); await upload(user);
    await user.click(screen.getByRole('button', { name: '全1件を確定して登録' }));
    expect(await screen.findByRole('table', { name: 'CSVエラー一覧' })).toHaveTextContent('変更後の顧客情報を確認');
    expect(screen.getByRole('button', { name: '全1件を確定して登録' })).toBeDisabled();
    await upload(user);
    expect(screen.queryByRole('table', { name: 'CSVエラー一覧' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '全1件を確定して登録' })).toBeEnabled();
    expect(fetch.mock.calls.filter(([path]) => String(path).endsWith('/confirm'))).toHaveLength(1);
  });

  it.each([{ status: 401, code: 'UNAUTHENTICATED' }, { status: 403, code: 'ACCESS_DENIED' }])('distinguishes listing session expiry from role denial %j', async (listingFailure) => {
    const fetch = mockServer({ listingFailure }); await start();
    expect(await screen.findByRole('alert')).toBeVisible();
    if (listingFailure.status === 401) expect(screen.getByLabelText('パスワード')).toHaveValue('');
    else expect(screen.getByText(session.email)).toBeVisible();
    expect(fetch.mock.calls.filter(([path]) => String(path).startsWith('/api/receivables?'))).toHaveLength(1);
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});
