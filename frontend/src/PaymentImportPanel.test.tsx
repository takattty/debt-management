import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import CsvImportPanel from './CsvImportPanel';
import type { ImportBatch, ImportError, PaymentImportRow, Session } from './api';

const session: Session = { userId: 'actor-a', tenantId: 'tenant-a', email: 'accountant@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const batch: ImportBatch = { id: 'batch-a', kind: 'PAYMENT', status: 'PREVIEW_VALID', version: 0, rowCount: 2, errorCount: 0, createdAt: '2026-10-30T00:00:00Z', expiresAt: '2099-10-30T00:00:00Z', confirmedAt: null, confirmedCount: 0 };
const row = (number: number): PaymentImportRow => ({ recordNumber: number, rowNumber: number + 2, transactionId: `TXN-${number}`, transferDate: '0001-01-01', payerName: '架空 ｶｸｳ é é', amount: '9007199254740991', valid: true });
const issue = (number: number): ImportError => ({ errorNumber: number, rowNumber: number + 2, field: 'amount', inputValue: '0', inputTruncated: false, reasonCode: 'INVALID_AMOUNT', reason: '金額は正の円整数で入力してください。', correction: '0円・出金・返金の行を除いてください。' });
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
function setup(options: { invalid?: boolean; paged?: boolean; denied?: { status: number; code: string } } = {}) {
  let uploads = 0;
  const fetch = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === '/api/session') return json(session);
    if (path === '/api/customers') return json([]);
    if (path === '/api/imports/payments/template') return new Response('\uFEFFtransaction_id,transfer_date,payer_name,amount\r\n');
    if (path === '/api/imports/payments/preview') {
      if (options.denied) return json({ code: options.denied.code }, options.denied.status);
      uploads++;
      return json({ ...batch, id: `batch-${uploads}`, rowCount: options.paged ? 10000 : 2, status: options.invalid && uploads === 1 ? 'PREVIEW_INVALID' : 'PREVIEW_VALID', errorCount: options.paged ? 51 : options.invalid && uploads === 1 ? 1 : 0 }, 201);
    }
    if (path.includes('/payment-rows?')) {
      const cursor = Number(new URL(path, 'http://example.test').searchParams.get('cursor'));
      return json({ items: options.paged ? Array.from({ length: 50 }, (_, index) => row(cursor + index + 1)) : [row(1), { ...row(2), amount: options.invalid && uploads === 1 ? '0' : '100000', valid: !(options.invalid && uploads === 1) }], nextCursor: options.paged ? cursor + 50 : 0 });
    }
    if (path.includes('/errors?')) {
      const cursor = Number(new URL(path, 'http://example.test').searchParams.get('cursor'));
      return json({ items: options.paged ? Array.from({ length: cursor ? 1 : 50 }, (_, index) => issue(cursor + index + 1)) : options.invalid && uploads === 1 ? [issue(2)] : [], nextCursor: options.paged && !cursor ? 50 : 0 });
    }
    if (path.endsWith('/errors.csv')) return new Response('row_number,field,input_value,reason,correction\n4,amount,0,正の円整数,0円は対象外\n');
    throw new Error(`Unexpected endpoint: ${path}`);
  });
  vi.stubGlobal('fetch', fetch); return fetch;
}
async function open() {
  const user = userEvent.setup(); render(<App />); await user.click(await screen.findByRole('button', { name: '入金CSV取込' })); return user;
}
async function upload(user: ReturnType<typeof userEvent.setup>) {
  await user.upload(screen.getByLabelText('入金CSVファイル'), new File(['transaction_id,transfer_date,payer_name,amount\nTXN-1,0001-01-01,架空振込人,100000\n'], 'payments.csv', { type: 'text/csv' }));
  await user.click(screen.getByRole('button', { name: 'CSVをプレビュー' }));
}

describe('Payment CSV dedicated preview', () => {
  it('uses generated payment rows and preserves raw invalid text and physical row guidance across repaired previews', async () => {
    const fetch = setup({ invalid: true }); const user = await open(); await upload(user);
    const errors = await screen.findByRole('table', { name: 'CSVエラー一覧' }); expect(errors).toHaveTextContent('4'); expect(errors).toHaveTextContent('0円・出金・返金');
    const table = screen.getByRole('table', { name: '入金CSVプレビュー' }); expect(table).toHaveTextContent('架空 ｶｸｳ é é'); expect(table).toHaveTextContent('0001-01-01'); expect(table).toHaveTextContent('9007199254740991'); expect(table).toHaveTextContent('要修正');
    expect(within(table).queryByText('顧客コード')).not.toBeInTheDocument(); expect(within(table).queryByText('請求書番号')).not.toBeInTheDocument();
    expect(screen.getByText(/まだ1件も登録されていません/)).toBeVisible();
    await upload(user); expect(screen.queryByRole('table', { name: 'CSVエラー一覧' })).not.toBeInTheDocument(); expect(screen.getByRole('table', { name: '入金CSVプレビュー' })).not.toHaveTextContent('要修正');
    expect(fetch.mock.calls.filter(([path]) => String(path).includes('/payment-rows?'))).toHaveLength(2);
    expect(fetch.mock.calls.some(([path]) => String(path).includes('/rows?'))).toBe(false);
  });
  it('pages payment rows and all errors without accumulating ten thousand rows in the DOM', async () => {
    const fetch = setup({ paged: true }); const user = await open(); await upload(user);
    const table = await screen.findByRole('table', { name: '入金CSVプレビュー' }); expect(within(table).getAllByRole('row')).toHaveLength(51);
    await user.click(screen.getByRole('button', { name: '次の50行' })); expect(await within(table).findByText('TXN-51')).toBeVisible(); expect(within(table).queryByText('TXN-1')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '次のエラー50件' })); expect(screen.getByRole('table', { name: 'CSVエラー一覧' })).toHaveTextContent('53');
    expect(fetch.mock.calls.some(([path]) => String(path).endsWith('/payment-rows?cursor=50&limit=50'))).toBe(true);
  });
  it('downloads payment template and correction CSV and keeps preview-only registration unavailable', async () => {
    setup({ invalid: true }); const blobs: Blob[] = [];
    class DownloadURL extends URL { static createObjectURL = vi.fn((blob: Blob) => { blobs.push(blob); return 'blob:csv'; }); static revokeObjectURL = vi.fn(); }
    vi.stubGlobal('URL', DownloadURL); vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const user = userEvent.setup(); render(<CsvImportPanel kind="PAYMENT" confirmationEnabled={false} session={session} onSessionExpired={vi.fn()} onBusyChange={vi.fn()} />);
    await user.click(screen.getByRole('button', { name: 'テンプレートをダウンロード' }));
    expect(blobs).toHaveLength(1); await upload(user); await screen.findByRole('table', { name: 'CSVエラー一覧' });
    await user.click(screen.getByRole('button', { name: '全エラーをCSVでダウンロード' })); expect(blobs).toHaveLength(2);
    expect(screen.queryByRole('button', { name: /確定して登録/ })).not.toBeInTheDocument();
  });
  it.each([{ status: 401, code: 'UNAUTHENTICATED' }, { status: 403, code: 'CSRF_INVALID' }, { status: 403, code: 'ACCESS_DENIED' }])('handles session expiry and access refusal separately %j', async (denied) => {
    const fetch = setup({ denied }); const user = await open(); await upload(user); await screen.findByRole('alert');
    if (denied.code === 'ACCESS_DENIED') expect(screen.getByText(session.email)).toBeVisible();
    else { expect(screen.getByLabelText('パスワード')).toHaveValue(''); expect(screen.queryByLabelText('入金CSVファイル')).not.toBeInTheDocument(); }
    expect(fetch.mock.calls.filter(([path]) => String(path).endsWith('/preview'))).toHaveLength(1);
  });
  it('discards the selected file and preview when leaving the payment import screen', async () => {
    setup(); const user = await open(); await upload(user); await screen.findByRole('table', { name: '入金CSVプレビュー' });
    await user.click(screen.getByRole('button', { name: '顧客管理' })); await user.click(screen.getByRole('button', { name: '入金CSV取込' }));
    expect(screen.queryByRole('table', { name: '入金CSVプレビュー' })).not.toBeInTheDocument(); expect(screen.getByLabelText('入金CSVファイル')).toHaveValue('');
  });
});
