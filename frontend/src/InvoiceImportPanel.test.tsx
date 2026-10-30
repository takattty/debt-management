import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import type { ImportBatch, ImportError, ImportRow, Session } from './api';

const session: Session = { userId: '11111111-1111-1111-1111-111111111112', tenantId: '11111111-1111-1111-1111-111111111111', email: 'alpha-accountant@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const batch: ImportBatch = { id: '11111111-1111-1111-1111-111111111170', kind: 'INVOICE', status: 'PREVIEW_VALID', version: 0, rowCount: 2, errorCount: 0, createdAt: '2026-10-30T00:00:00Z', expiresAt: '2099-10-30T00:00:00Z', confirmedAt: null, confirmedCount: 0 };
const invoiceRow = (number: number): ImportRow => ({ recordNumber: number, rowNumber: number + 1, customerCode: 'C001', customerName: '架空商事', invoiceNumber: `INV-${number}`, issueDate: '2026-10-30', dueDate: '2026-11-30', amount: '100000', valid: true });
const issue: ImportError = { errorNumber: 1, rowNumber: 3, field: 'amount', inputValue: 'abc', inputTruncated: false, reasonCode: 'INVALID_AMOUNT', reason: '金額は正の円整数で入力してください。', correction: '金額を100000のような正の整数に修正してください。' };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });

function mockServer(options: { invalid?: boolean; tenThousand?: boolean; wrongKind?: boolean; denied?: string } = {}) {
  let uploads = 0;
  const fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === '/api/session') return json(session);
    if (path === '/api/customers') return json([]);
    if (path === '/api/imports/invoices/preview') {
      if (options.denied) return json({ code: options.denied }, 403);
      uploads++;
      return json({ ...batch, rowCount: options.tenThousand ? 10000 : 2, ...(options.invalid && uploads === 1 ? { status: 'PREVIEW_INVALID', errorCount: 1 } : {}), ...(options.wrongKind ? { kind: 'CUSTOMER' } : {}) }, 201);
    }
    if (path.includes('/rows?')) {
      const cursor = Number(new URL(path, 'http://example.test').searchParams.get('cursor'));
      return json({ items: options.tenThousand ? Array.from({ length: 50 }, (_, index) => invoiceRow(cursor + index + 1)) : [invoiceRow(1), { ...invoiceRow(2), amount: options.invalid && uploads === 1 ? 'abc' : '200000', valid: !(options.invalid && uploads === 1) }], nextCursor: options.tenThousand ? cursor + 50 : 0 });
    }
    if (path.includes('/errors?')) return json({ items: options.invalid && uploads === 1 ? [issue] : [], nextCursor: 0 });
    if (path.endsWith('/errors.csv')) return new Response('\uFEFFrow_number,field,input_value,reason,correction\r\n3,amount,abc,正の円整数,100000のように修正\r\n');
    throw new Error(`Unexpected endpoint: ${path}`);
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

async function open() {
  const user = userEvent.setup(); render(<App />);
  await user.click(await screen.findByRole('button', { name: '請求CSV取込' }));
  return user;
}

async function upload(user: ReturnType<typeof userEvent.setup>) {
  await user.upload(screen.getByLabelText('請求CSVファイル'), new File(['customer_code,invoice_number,issue_date,due_date,amount\nC001,INV-1,2026-10-30,2026-11-30,100000\n'], 'invoices.csv', { type: 'text/csv' }));
  await user.click(screen.getByRole('button', { name: 'CSVをプレビュー' }));
}

describe('Invoice CSV preview', () => {
  it('keeps invalid amount text and physical correction details, then enables confirmation only after a repaired preview', async () => {
    const fetch = mockServer({ invalid: true });
    const user = await open(); await upload(user);
    expect(await screen.findByRole('table', { name: 'CSVエラー一覧' })).toHaveTextContent('100000のような正の整数');
    expect(within(screen.getByRole('table', { name: '請求CSVプレビュー' })).getByText('abc')).toBeVisible();
    expect(screen.getByText(/まだ1件も登録されていません/)).toBeVisible();
    expect(screen.getByRole('button', { name: /確定して登録/ })).toBeDisabled();
    const sent = fetch.mock.calls.find(([path]) => path === '/api/imports/invoices/preview')?.[1];
    expect(sent.body).toBeInstanceOf(FormData);
    expect(sent.headers).not.toHaveProperty('Content-Type');
    expect(sent.headers['X-CSRF-TOKEN']).toBe(session.csrfToken);
    await upload(user);
    expect(screen.queryByRole('table', { name: 'CSVエラー一覧' })).not.toBeInTheDocument();
    expect(await screen.findByRole('table', { name: '請求CSVプレビュー' })).toHaveTextContent('200000');
    expect(screen.getByRole('button', { name: /確定して登録/ })).toBeEnabled();
    expect(fetch.mock.calls.some(([path]) => String(path).endsWith('/confirm'))).toBe(false);
  });

  it('keeps a 10000-row preview bounded to50 displayed records and fetches the next page explicitly', async () => {
    const fetch = mockServer({ tenThousand: true });
    const user = await open(); await upload(user);
    const table = await screen.findByRole('table', { name: '請求CSVプレビュー' });
    expect(within(table).getAllByRole('row')).toHaveLength(51);
    expect(screen.getByText('全10000行中 1〜50行を表示')).toBeVisible();
    await user.click(screen.getByRole('button', { name: '次の50行' }));
    expect(await screen.findByText('全10000行中 51〜100行を表示')).toBeVisible();
    expect(within(table).getAllByRole('row')).toHaveLength(51);
    expect(fetch.mock.calls.filter(([path]) => String(path).includes('/rows?'))).toHaveLength(2);
  });

  it('clears preview state when switching between invoice and customer CSV', async () => {
    mockServer(); const user = await open(); await upload(user);
    await screen.findByRole('table', { name: '請求CSVプレビュー' });
    await user.click(screen.getByRole('button', { name: '顧客CSV取込' }));
    expect(screen.queryByRole('heading', { name: 'プレビュー内容' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('顧客CSVファイル')).toBeVisible();
    await user.click(screen.getByRole('button', { name: '請求CSV取込' }));
    expect((screen.getByLabelText('請求CSVファイル') as HTMLInputElement).files).toHaveLength(0);
  });

  it('refuses a batch of the wrong import kind without displaying its rows', async () => {
    mockServer({ wrongKind: true }); const user = await open(); await upload(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('取込形式が一致しません');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('clears the entire workspace after invoice upload CSRF expiry', async () => {
    mockServer({ denied: 'CSRF_INVALID' }); const user = await open(); await upload(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('ログインの有効期限が切れました');
    expect(screen.getByLabelText('パスワード')).toHaveValue('');
    expect(screen.queryByLabelText('請求CSVファイル')).not.toBeInTheDocument();
  });
});
