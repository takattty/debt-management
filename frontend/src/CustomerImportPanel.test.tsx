import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import type { Customer, ImportBatch, ImportError, ImportRow, Session } from './api';

const session: Session = {
  userId: '11111111-1111-1111-1111-111111111112', tenantId: '11111111-1111-1111-1111-111111111111',
  email: 'alpha-accountant@example.test', role: 'ACCOUNTANT', csrfToken: 'session-csrf', csrfHeaderName: 'X-CSRF-TOKEN',
};
const batchId = '11111111-1111-1111-1111-111111111120';
const makeBatch = (overrides: Partial<ImportBatch> = {}): ImportBatch => ({
  id: batchId, kind: 'CUSTOMER', status: 'PREVIEW_VALID', version: 0, rowCount: 2, errorCount: 0,
  createdAt: '2026-10-29T00:00:00Z', expiresAt: '2099-10-30T00:00:00Z', confirmedAt: null, confirmedCount: 0, ...overrides,
});
const row = (recordNumber: number, valid = true): ImportRow => ({ recordNumber, rowNumber: recordNumber + 1, customerCode: `CSV-${recordNumber}`, customerName: `架空顧客${recordNumber}`, valid });
const problem = (errorNumber: number): ImportError => ({ errorNumber, rowNumber: errorNumber + 1, field: 'customer_name', inputValue: '', inputTruncated: false, reasonCode: 'REQUIRED', reason: '顧客名が空欄です。', correction: '顧客名を入力してください。' });
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const csvFile = () => new File(['customer_code,customer_name\nCSV-1,架空顧客1\nCSV-2,架空顧客2\n'], 'customers.csv', { type: 'text/csv' });

function mockServer(options: { invalidFirst?: boolean; previewFailure?: { status: number; code: string }; lostResponse?: boolean; truncatedResponse?: boolean; requestNotDelivered?: boolean; confirmFailure?: string; unavailableRecovery?: boolean; duplicate?: boolean; previouslyConfirmed?: boolean; paginated?: boolean; expired?: boolean } = {}) {
  let previewCount = 0;
  let lookupCount = 0;
  let customers: Customer[] = [];
  let current = makeBatch(options.expired ? { expiresAt: '2020-01-01T00:00:00Z' } : {});
  const fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === '/api/session') return json(session);
    if (path === '/api/customers') return json(customers);
    if (path === '/api/imports/customers/template') return new Response('\uFEFFcustomer_code,customer_name\r\n', { headers: { 'Content-Type': 'text/csv;charset=UTF-8' } });
    if (path === '/api/imports/customers/preview') {
      if (options.previewFailure) return json({ code: options.previewFailure.code }, options.previewFailure.status);
      previewCount++;
      current = makeBatch({ id: previewCount === 1 ? batchId : '11111111-1111-1111-1111-111111111121', ...(options.expired ? { expiresAt: '2020-01-01T00:00:00Z' } : {}), ...(options.invalidFirst && previewCount === 1 ? { status: 'PREVIEW_INVALID', errorCount: 1 } : {}), ...(options.paginated ? { status: 'PREVIEW_INVALID', rowCount: 51, errorCount: 51 } : {}), ...(options.previouslyConfirmed ? { status: 'PREVIEW_INVALID', errorCount: 2, originalBatchId: '11111111-1111-1111-1111-111111111122' } : {}) });
      return json(current, 201);
    }
    if (path.endsWith('/confirm')) {
      if (options.requestNotDelivered) throw new TypeError('Request not delivered');
      if (options.confirmFailure) return json({ code: options.confirmFailure }, 409);
      if (options.duplicate) return json({ code: 'IMPORT_ALREADY_CONFIRMED', errors: [{ field: 'batchId', message: '11111111-1111-1111-1111-111111111122' }] }, 409);
      current = { ...current, status: 'CONFIRMED', version: 1, confirmedAt: '2026-10-29T01:00:00Z', confirmedCount: 2 };
      customers = [1, 2].map((number) => ({ id: `customer-${number}`, code: `CSV-${number}`, name: `架空顧客${number}`, version: 0 }));
      if (options.lostResponse) throw new TypeError('Response unavailable');
      if (options.truncatedResponse) return new Response('{"id":');
      return json(current);
    }
    if (path.endsWith('/errors.csv')) return new Response('\uFEFFrow_number,field,input_value,input_truncated,reason,correction\r\n3,customer_name,,false,顧客名が空欄です。,顧客名を入力してください。\r\n', { headers: { 'Content-Type': 'text/csv;charset=UTF-8' } });
    if (path.includes('/rows?')) return json({ items: [row(1), row(2, current.errorCount === 0)], nextCursor: 0 });
    if (path.includes('/errors?')) {
      const cursor = Number(new URL(path, 'http://example.test').searchParams.get('cursor'));
      return json({ items: options.paginated ? Array.from({ length: cursor === 0 ? 50 : 1 }, (_, index) => problem(cursor + index + 1)) : current.errorCount ? [problem(2)] : [], nextCursor: options.paginated && cursor === 0 ? 50 : 0 });
    }
    if (path.startsWith('/api/imports/')) {
      lookupCount++;
      if (options.unavailableRecovery && lookupCount === 1) throw new TypeError('Lookup unavailable');
      return json(options.duplicate || options.previouslyConfirmed ? { ...current, id: '11111111-1111-1111-1111-111111111122', status: 'CONFIRMED', errorCount: 0, confirmedCount: 2, confirmedAt: '2026-10-29T01:00:00Z', version: 1, originalBatchId: null } : current);
    }
    throw new Error(`Unexpected endpoint: ${path}`);
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

async function openImport() {
  const user = userEvent.setup();
  render(<App />);
  await user.click(await screen.findByRole('button', { name: '顧客CSV取込' }));
  return user;
}

async function upload(user: ReturnType<typeof userEvent.setup>) {
  await user.upload(screen.getByLabelText('顧客CSVファイル'), csvFile());
  await user.click(screen.getByRole('button', { name: 'CSVをプレビュー' }));
  await screen.findByRole('heading', { name: 'プレビュー内容' });
}

describe('Customer CSV workflow', () => {
  it('shows correction details, downloads all errors, then confirms a repaired CSV and refreshes the stored customer list', async () => {
    const fetch = mockServer({ invalidFirst: true });
    const blobs: Blob[] = [];
    class DownloadURL extends URL { static createObjectURL = vi.fn((blob: Blob) => { blobs.push(blob); return 'blob:download'; }); static revokeObjectURL = vi.fn(); }
    vi.stubGlobal('URL', DownloadURL);
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const user = await openImport();
    await upload(user);
    expect(await screen.findByRole('table', { name: 'CSVエラー一覧' })).toHaveTextContent('顧客名を入力してください');
    expect(screen.getByRole('heading', { name: '修正が必要な項目' })).toHaveFocus();
    expect(screen.getByText(/まだ1件も登録されていません/)).toBeVisible();
    expect(screen.getByRole('button', { name: '全2件を確定して登録' })).toBeDisabled();
    const previewRequest = fetch.mock.calls.find(([path]) => path === '/api/imports/customers/preview')?.[1];
    expect(previewRequest.body).toBeInstanceOf(FormData);
    expect(previewRequest.body.get('file')).toBeInstanceOf(File);
    expect(previewRequest.headers).not.toHaveProperty('Content-Type');
    expect(previewRequest.headers['X-CSRF-TOKEN']).toBe(session.csrfToken);
    await user.click(screen.getByRole('button', { name: '全エラーをCSVでダウンロード' }));
    expect(click).toHaveBeenCalledOnce();
    const downloaded = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = reject; reader.readAsText(blobs[0]);
    });
    expect(downloaded).toContain('顧客名を入力してください');
    await user.upload(screen.getByLabelText('顧客CSVファイル'), csvFile());
    expect(screen.queryByRole('heading', { name: 'プレビュー内容' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'CSVをプレビュー' }));
    expect(await screen.findByRole('button', { name: '全2件を確定して登録' })).toBeEnabled();
    expect(screen.queryByRole('table', { name: 'CSVエラー一覧' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '全2件を確定して登録' }));
    expect(await screen.findByRole('status')).toHaveTextContent('2件の顧客を登録しました');
    const confirmations = fetch.mock.calls.filter(([path]) => String(path).endsWith('/confirm'));
    expect(confirmations).toHaveLength(1);
    expect(JSON.parse(confirmations[0][1].body)).toEqual({ version: 0 });
    await user.click(screen.getByRole('button', { name: '顧客一覧へ' }));
    expect(within(await screen.findByRole('table')).getByText('架空顧客1')).toBeVisible();
    expect(within(screen.getByRole('table')).getByText('架空顧客2')).toBeVisible();
    expect(screen.getByText(session.email)).toBeVisible();
  });

  it('pages all correction details without loading an unbounded table', async () => {
    const fetch = mockServer({ paginated: true });
    const user = await openImport();
    await upload(user);
    const table = await screen.findByRole('table', { name: 'CSVエラー一覧' });
    expect(within(table).getAllByRole('row')).toHaveLength(51);
    await user.click(screen.getByRole('button', { name: '次のエラー50件' }));
    expect(await screen.findByText('全51件中 51〜51件を表示')).toBeVisible();
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    expect(screen.getByRole('button', { name: '次のエラー50件' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '最初のエラーへ' }));
    expect(await screen.findByText('全51件中 1〜50件を表示')).toBeVisible();
    expect(fetch.mock.calls.some(([path]) => String(path).includes('/errors?cursor=50&limit=50'))).toBe(true);
  });

  it.each([{ lostResponse: true }, { truncatedResponse: true }])('recovers an already committed result after an unreadable POST response (%j) without sending confirmation twice', async (failure) => {
    const fetch = mockServer(failure);
    const user = await openImport();
    await upload(user);
    await user.click(screen.getByRole('button', { name: '全2件を確定して登録' }));
    expect(await screen.findByRole('status')).toHaveTextContent('2件の顧客を登録しました');
    expect(fetch.mock.calls.filter(([path]) => String(path).endsWith('/confirm'))).toHaveLength(1);
    expect(fetch.mock.calls.filter(([path]) => path === `/api/imports/${batchId}`)).toHaveLength(1);
    expect(screen.queryByRole('button', { name: '全2件を確定して登録' })).not.toBeInTheDocument();
  });

  it('requires explicit confirmation again when the initial request never reached the server', async () => {
    const fetch = mockServer({ requestNotDelivered: true });
    const user = await openImport();
    await upload(user);
    await user.click(screen.getByRole('button', { name: '全2件を確定して登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('現在は未確定です');
    expect(screen.getByRole('button', { name: '全2件を確定して登録' })).toBeEnabled();
    expect(fetch.mock.calls.filter(([path]) => String(path).endsWith('/confirm'))).toHaveLength(1);
  });

  it('requires a new preview after a customer-code conflict instead of resending the stale batch', async () => {
    mockServer({ confirmFailure: 'IMPORT_REFERENCE_CONFLICT' });
    const user = await openImport();
    await upload(user);
    await user.click(screen.getByRole('button', { name: '全2件を確定して登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('CSVを修正して、もう一度プレビュー');
    expect(screen.getByRole('button', { name: '全2件を確定して登録' })).toBeDisabled();
  });

  it('blocks another confirmation until an uncertain server result is explicitly recovered', async () => {
    const fetch = mockServer({ lostResponse: true, unavailableRecovery: true });
    const user = await openImport();
    await upload(user);
    await user.click(screen.getByRole('button', { name: '全2件を確定して登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('サーバーに接続できません');
    expect(screen.getByRole('button', { name: '全2件を確定して登録' })).toBeDisabled();
    expect(screen.getByLabelText('顧客CSVファイル')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'CSVをプレビュー' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '確定結果を確認' }));
    expect(await screen.findByRole('status')).toHaveTextContent('2件の顧客を登録しました');
    expect(fetch.mock.calls.filter(([path]) => String(path).endsWith('/confirm'))).toHaveLength(1);
  });

  it('offers the original server result when another batch has already confirmed the same content', async () => {
    const fetch = mockServer({ duplicate: true });
    const user = await openImport();
    await upload(user);
    await user.click(screen.getByRole('button', { name: '全2件を確定して登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('同じ内容はすでに取り込まれています');
    await user.click(screen.getByRole('button', { name: '元の取込結果を確認' }));
    expect(await screen.findByRole('heading', { name: '取込結果' })).toBeVisible();
    expect(fetch.mock.calls.some(([path]) => path === '/api/imports/11111111-1111-1111-1111-111111111122')).toBe(true);
  });

  it('links a duplicate reupload to the original confirmed batch before sending any confirmation', async () => {
    const fetch = mockServer({ previouslyConfirmed: true });
    const user = await openImport();
    await upload(user);
    expect(screen.getByRole('button', { name: '全2件を確定して登録' })).toBeDisabled();
    expect(screen.getByText(/同じ内容のCSVはすでに確定されています/)).toBeVisible();
    await user.click(screen.getByRole('button', { name: '元の取込結果を確認' }));
    expect(await screen.findByRole('heading', { name: '取込結果' })).toBeVisible();
    expect(fetch.mock.calls.filter(([path]) => String(path).endsWith('/confirm'))).toHaveLength(0);
  });

  it('clears the import and customer workspace on CSRF expiry', async () => {
    mockServer({ previewFailure: { status: 403, code: 'CSRF_INVALID' } });
    const user = await openImport();
    await user.upload(screen.getByLabelText('顧客CSVファイル'), csvFile());
    await user.click(screen.getByRole('button', { name: 'CSVをプレビュー' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('ログインの有効期限が切れました');
    expect(screen.getByLabelText('パスワード')).toHaveValue('');
    expect(screen.queryByLabelText('顧客CSVファイル')).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('keeps the valid session and selected file after a role denial without automatically resending', async () => {
    const fetch = mockServer({ previewFailure: { status: 403, code: 'ACCESS_DENIED' } });
    const user = await openImport();
    await user.upload(screen.getByLabelText('顧客CSVファイル'), csvFile());
    await user.click(screen.getByRole('button', { name: 'CSVをプレビュー' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('この操作を行う権限がありません');
    expect(screen.getByText(session.email)).toBeVisible();
    expect((screen.getByLabelText('顧客CSVファイル') as HTMLInputElement).files).toHaveLength(1);
    expect(fetch.mock.calls.filter(([path]) => path === '/api/imports/customers/preview')).toHaveLength(1);
  });

  it('discards the preview when leaving and refuses to confirm an expired preview', async () => {
    mockServer({ expired: true });
    const user = await openImport();
    await upload(user);
    expect(screen.getByText('有効期限が切れています。CSVをもう一度プレビューしてください。')).toBeVisible();
    expect(screen.getByRole('button', { name: '全2件を確定して登録' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '顧客管理' }));
    await user.click(screen.getByRole('button', { name: '顧客CSV取込' }));
    expect(screen.queryByRole('heading', { name: 'プレビュー内容' })).not.toBeInTheDocument();
    expect((screen.getByLabelText('顧客CSVファイル') as HTMLInputElement).files).toHaveLength(0);
  });
});
