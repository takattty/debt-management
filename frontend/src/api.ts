import { conflictGuidance } from './conflict-guidance';
import type { components, operations, paths } from './generated/schema';

export type Customer = components['schemas']['CustomerResponse'];
export type Session = components['schemas']['SessionResponse'];
export type InvitationRole = components['schemas']['InvitationIssueRequest']['role'];
export type InvitationInspection = components['schemas']['InvitationInspectionResponse'];
export type InvitationIssued = components['schemas']['InvitationIssuedResponse'];
export type ImportBatch = components['schemas']['ImportBatchResponse'];
export type ImportRow = components['schemas']['ImportRowResponse'];
export type PaymentImportRow = components['schemas']['PaymentImportRowResponse'];
export type AllocationHistory = components['schemas']['AllocationHistoryEntry'];
export type AllocationHistoryPage = components['schemas']['AllocationHistoryPage'];
export type AllocationReversal = operations['reverseAllocation']['requestBody']['content']['application/json'];
export type AllocationConfirmation = operations['confirmAllocation']['requestBody']['content']['application/json'];
export type PaymentCandidate = components['schemas']['PaymentCandidateResponse'];
export type PaymentCandidates = components['schemas']['PaymentCandidatesPage'];
export type Payment = components['schemas']['PaymentResponse'];
export type PaymentsQuery = NonNullable<operations['listPayments']['parameters']['query']>;
export type PayerAlias = components['schemas']['PayerAliasResponse'];
export type ImportError = components['schemas']['ImportErrorResponse'];
export type Receivable = components['schemas']['ReceivableResponse'];
export type Invoice = components['schemas']['InvoiceResponse'];
export type InvoiceHistory = components['schemas']['InvoiceHistoryEntry'];
export type ReceivablesQuery = NonNullable<operations['listReceivables']['parameters']['query']>;
export type CollectionCase = components['schemas']['CollectionCaseResponse'];
export type CollectionHistory = components['schemas']['CollectionHistoryEntry'];
export type CollectionDraft = components['schemas']['CollectionDraftResponse'];
export type CollectionsQuery = operations['listCollections']['parameters']['query'];
type Csrf = components['schemas']['CsrfResponse'];
type LoginInput = operations['login']['requestBody']['content']['application/json'];
type CustomerCreateInput = operations['createCustomer']['requestBody']['content']['application/json'];

type Endpoint = {
  [Path in keyof paths]: {
    [Method in keyof paths[Path]]-?: paths[Path][Method] extends { responses: unknown }
      ? { path: Path; method: Uppercase<Method & string> }
      : never;
  }[keyof paths[Path]];
}[keyof paths];

type OperationOf<E extends Endpoint> = E extends { path: infer Path extends keyof paths; method: infer Method extends string }
  ? paths[Path][Lowercase<Method> & keyof paths[Path]] : never;
type JsonResponse<Response> = Response extends { content: { 'application/json': infer Body } } ? Body : void;
type SuccessOf<Operation> = Operation extends { responses: infer Responses }
  ? JsonResponse<Responses[keyof Responses & (200 | 201 | 204)]> : never;
type RequestBody<E extends Endpoint> = OperationOf<E> extends { requestBody: { content: { 'application/json': infer Body } } }
  ? [body: Body] : [];

// Only operation-specific functions are exposed to UI code; DTOs come from the contract.
export const api = {
  listCollections: (query: CollectionsQuery) => request({ path: '/api/collections', method: 'GET', url: `/api/collections?${queryString(query)}` }, undefined),
  getCollection: (id: string, asOf: string) => request({ path: '/api/collections/{id}', method: 'GET', url: `${collectionUrl(id)}?${queryString({ asOf })}` }, undefined),
  listCollectionHistory: (id: string, cursor = 0) => request({ path: '/api/collections/{id}/history', method: 'GET', url: `${collectionUrl(id)}/history?cursor=${cursor}&limit=50` }, undefined),
  recordCollectionAction: (csrf: Csrf, id: string, body: operations['recordCollectionAction']['requestBody']['content']['application/json']) => request({ path: '/api/collections/{id}/actions', method: 'POST', url: `${collectionUrl(id)}/actions` }, csrf, body),
  holdCollection: (csrf: Csrf, id: string, body: operations['holdCollection']['requestBody']['content']['application/json']) => request({ path: '/api/collections/{id}/hold', method: 'POST', url: `${collectionUrl(id)}/hold` }, csrf, body),
  releaseCollection: (csrf: Csrf, id: string, body: operations['releaseCollection']['requestBody']['content']['application/json']) => request({ path: '/api/collections/{id}/release', method: 'POST', url: `${collectionUrl(id)}/release` }, csrf, body),
  previewCollectionDraft: (id: string, asOf: string) => request({ path: '/api/collections/{id}/draft', method: 'GET', url: `${collectionUrl(id)}/draft?${queryString({ asOf })}` }, undefined),
  getCsrf: () => request({ path: '/api/csrf', method: 'GET' }, undefined),
  login: (body: LoginInput, csrf: Csrf) => request({ path: '/api/auth/login', method: 'POST' }, csrf, body),
  getSession: () => request({ path: '/api/session', method: 'GET' }, undefined),
  listCustomers: () => request({ path: '/api/customers', method: 'GET' }, undefined),
  listPayerAliases: (cursor?: string) => request({ path: '/api/payer-aliases', method: 'GET', url: `/api/payer-aliases?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}` }, undefined),
  getPayerAlias: (id: string) => request({ path: '/api/payer-aliases/{id}', method: 'GET', url: payerAliasUrl(id) }, undefined),
  createPayerAlias: (csrf: Csrf, body: operations['createPayerAlias']['requestBody']['content']['application/json']) => request(
    { path: '/api/payer-aliases', method: 'POST' }, csrf, body,
  ),
  deactivatePayerAlias: (csrf: Csrf, id: string, body: operations['deactivatePayerAlias']['requestBody']['content']['application/json']) => request(
    { path: '/api/payer-aliases/{id}/deactivation', method: 'POST', url: `${payerAliasUrl(id)}/deactivation` }, csrf, body,
  ),
  listPayments: (query: PaymentsQuery = {}) => {
    const parameters = new URLSearchParams();
    Object.entries(query).forEach(([key, value]) => { if (value !== undefined) parameters.set(key, String(value)); });
    return request({ path: '/api/payments', method: 'GET', url: `/api/payments?${parameters}` }, undefined);
  },
  listAllocationHistory: (id: string, cursor?: string) => request({ path: '/api/payments/{paymentId}/allocations', method: 'GET', url: `/api/payments/${encodeURIComponent(id)}/allocations?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}` }, undefined),
  reverseAllocation: (csrf: Csrf, id: string, operationId: string, body: AllocationReversal) => request({ path: '/api/payments/{paymentId}/allocations/{allocationId}/reversal', method: 'POST', url: `/api/payments/${encodeURIComponent(id)}/allocations/${encodeURIComponent(operationId)}/reversal` }, csrf, body),
  confirmAllocation: (csrf: Csrf, id: string, body: AllocationConfirmation) => request({ path: '/api/payments/{paymentId}/allocations', method: 'POST', url: `/api/payments/${encodeURIComponent(id)}/allocations` }, csrf, body),
  listPaymentCandidates: (id: string, cursor?: string) => request({ path: '/api/payments/{id}/candidates', method: 'GET', url: `/api/payments/${encodeURIComponent(id)}/candidates?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}` }, undefined),
  getInvoice: (id: string) => request({ path: '/api/invoices/{id}', method: 'GET', url: invoiceUrl(id) }, undefined),
  listInvoiceHistory: (id: string, cursor = -1) => request({ path: '/api/invoices/{id}/history', method: 'GET', url: `${invoiceUrl(id)}/history?cursor=${cursor}&limit=50` }, undefined),
  correctInvoice: (csrf: Csrf, id: string, body: operations['correctInvoice']['requestBody']['content']['application/json']) => request(
    { path: '/api/invoices/{id}', method: 'PUT', url: invoiceUrl(id) }, csrf, body,
  ),
  cancelInvoice: (csrf: Csrf, id: string, body: operations['cancelInvoice']['requestBody']['content']['application/json']) => request(
    { path: '/api/invoices/{id}/cancel', method: 'POST', url: `${invoiceUrl(id)}/cancel` }, csrf, body,
  ),
  listReceivables: (query: ReceivablesQuery = {}) => {
    const parameters = new URLSearchParams();
    Object.entries(query).forEach(([key, value]) => { if (value !== undefined) parameters.set(key, String(value)); });
    return request({ path: '/api/receivables', method: 'GET', url: `/api/receivables?${parameters}` }, undefined);
  },
  createCustomer: (csrf: Csrf, body: CustomerCreateInput) => request(
    { path: '/api/customers', method: 'POST' }, csrf, body,
  ),
  updateCustomer: (csrf: Csrf, id: string, body: operations['updateCustomer']['requestBody']['content']['application/json']) => request(
    { path: '/api/customers/{id}', method: 'PUT', url: `/api/customers/${encodeURIComponent(id)}` }, csrf, body,
  ),
  logout: (csrf: Csrf) => request({ path: '/api/logout', method: 'POST' }, csrf),
  issueInvitation: (csrf: Csrf, body: operations['issueInvitation']['requestBody']['content']['application/json']) => request(
    { path: '/api/invitations', method: 'POST' }, csrf, body,
  ),
  inspectInvitation: (csrf: Csrf, body: operations['inspectInvitation']['requestBody']['content']['application/json']) => request(
    { path: '/api/invitations/inspect', method: 'POST' }, csrf, body,
  ),
  acceptInvitation: (csrf: Csrf, body: operations['acceptInvitation']['requestBody']['content']['application/json']) => request(
    { path: '/api/invitations/accept', method: 'POST' }, csrf, body,
  ),
  downloadCustomerImportTemplate: () => download({ path: '/api/imports/customers/template', method: 'GET' }),
  downloadInvoiceImportTemplate: () => download({ path: '/api/imports/invoices/template', method: 'GET' }),
  downloadPaymentImportTemplate: () => download({ path: '/api/imports/payments/template', method: 'GET' }),
  previewPaymentImport: async (csrf: Csrf, file: File): Promise<ImportBatch> => {
    const body = new FormData();
    const field: keyof operations['previewPaymentImport']['requestBody']['content']['multipart/form-data'] = 'file';
    body.append(field, file);
    return readJson<ImportBatch>(await send({ path: '/api/imports/payments/preview', method: 'POST' }, csrf, body));
  },
  previewInvoiceImport: async (csrf: Csrf, file: File): Promise<ImportBatch> => {
    const body = new FormData();
    const field: keyof operations['previewInvoiceImport']['requestBody']['content']['multipart/form-data'] = 'file';
    body.append(field, file);
    return readJson<ImportBatch>(await send({ path: '/api/imports/invoices/preview', method: 'POST' }, csrf, body));
  },
  previewCustomerImport: async (csrf: Csrf, file: File): Promise<ImportBatch> => {
    const body = new FormData();
    const field: keyof operations['previewCustomerImport']['requestBody']['content']['multipart/form-data'] = 'file';
    body.append(field, file);
    return readJson<ImportBatch>(await send({ path: '/api/imports/customers/preview', method: 'POST' }, csrf, body));
  },
  getImportBatch: (id: string) => request({ path: '/api/imports/{id}', method: 'GET', url: importUrl(id) }, undefined),
  listImportRows: (id: string, cursor = 0) => request({ path: '/api/imports/{id}/rows', method: 'GET', url: `${importUrl(id)}/rows?cursor=${cursor}&limit=50` }, undefined),
  listPaymentImportRows: (id: string, cursor = 0) => request({ path: '/api/imports/{id}/payment-rows', method: 'GET', url: `${importUrl(id)}/payment-rows?cursor=${cursor}&limit=50` }, undefined),
  listImportErrors: (id: string, cursor = 0) => request({ path: '/api/imports/{id}/errors', method: 'GET', url: `${importUrl(id)}/errors?cursor=${cursor}&limit=50` }, undefined),
  downloadImportErrors: (id: string) => download({ path: '/api/imports/{id}/errors.csv', method: 'GET', url: `${importUrl(id)}/errors.csv` }),
  confirmImport: (csrf: Csrf, id: string, body: operations['confirmImport']['requestBody']['content']['application/json']) => request(
    { path: '/api/imports/{id}/confirm', method: 'POST', url: `${importUrl(id)}/confirm` }, csrf, body,
  ),
};

export class ApiError extends Error {
  constructor(public status: number, message: string, public code?: components['schemas']['ApiError']['code'], public details: components['schemas']['FieldError'][] = []) { super(message); }
}

type RequestEndpoint<E extends Endpoint = Endpoint> = E & { url?: string };
const importUrl = (id: string) => `/api/imports/${encodeURIComponent(id)}`;
const invoiceUrl = (id: string) => `/api/invoices/${encodeURIComponent(id)}`;
const payerAliasUrl = (id: string) => `/api/payer-aliases/${encodeURIComponent(id)}`;
const collectionUrl = (id: string) => `/api/collections/${encodeURIComponent(id)}`;
const queryString = (query: object) => new URLSearchParams(Object.entries(query).filter(([, value]) => value !== undefined).map(([key, value]) => [key, String(value)])).toString();

async function request<E extends Endpoint>(endpoint: RequestEndpoint<E>, csrf: Csrf | undefined, ...body: RequestBody<E>): Promise<SuccessOf<OperationOf<E>>> {
  const response = await send(endpoint, csrf, body.length ? JSON.stringify(body[0]) : undefined);
  if (response.status === 204) return undefined as SuccessOf<OperationOf<E>>;
  return readJson<SuccessOf<OperationOf<E>>>(response);
}

async function readJson<T>(response: Response): Promise<T> {
  try { return await response.json(); }
  catch { throw new ApiError(0, 'サーバーの応答を確認できませんでした。接続を確認して、処理結果を再確認してください。'); }
}

async function download(endpoint: RequestEndpoint) {
  const response = await send(endpoint, undefined);
  try { return await response.blob(); }
  catch { throw new ApiError(0, 'CSVをダウンロードできませんでした。接続を確認して、もう一度お試しください。'); }
}

async function send(endpoint: RequestEndpoint, csrf: Csrf | undefined, body?: BodyInit): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(endpoint.url ?? endpoint.path, {
      method: endpoint.method,
      body,
      credentials: 'same-origin',
      cache: 'no-store',
      headers: {
        ...(body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
        'X-Requested-With': 'XMLHttpRequest',
        ...(csrf ? { [csrf.csrfHeaderName]: csrf.csrfToken } : {}),
      },
    });
  } catch {
    throw new ApiError(0, 'サーバーに接続できません。接続を確認して、もう一度お試しください。');
  }
  if (!response.ok) {
    const failure: unknown = await response.json().catch(() => undefined);
    const code = typeof failure === 'object' && failure !== null && 'code' in failure && typeof failure.code === 'string'
      ? failure.code : undefined;
    const invitation = endpoint.path.startsWith('/api/invitations');
    const importing = endpoint.path.startsWith('/api/imports');
    const allocating = endpoint.path.startsWith('/api/payments/');
    const invoicing = endpoint.path.startsWith('/api/invoices');
    const aliasing = endpoint.path.startsWith('/api/payer-aliases');
    const collecting = endpoint.path.startsWith('/api/collections');
    const messages: Record<number, string> = {
      400: endpoint.path === '/api/auth/login' ? 'メールアドレスとパスワードの入力内容を確認してください。'
        : endpoint.path === '/api/invitations' ? '招待先のメールアドレスと権限を確認してください。'
          : invitation ? '入力したメールアドレスとパスワードの条件を確認してください。'
            : importing ? 'CSVファイルと入力形式を確認して、もう一度プレビューしてください。'
              : endpoint.path === '/api/receivables' ? '顧客と支払期日の検索条件を確認してください。'
                : endpoint.path === '/api/payments' ? '振込日の検索条件を確認してください。' : '顧客コード・顧客名の形式や文字数を確認してください。',
      401: endpoint.path === '/api/auth/login' ? 'メールアドレスまたはパスワードを確認してください。' : 'ログインの有効期限が切れました。もう一度ログインしてください。',
      403: code === 'ACCESS_DENIED' ? 'この操作を行う権限がありません。管理者にご確認ください。'
        : code === 'CSRF_INVALID' ? 'ログインの有効期限が切れました。もう一度ログインしてください。'
          : '操作を続けられません。画面を再読み込みして、もう一度お試しください。',
      404: importing ? '取込内容を表示できません。CSVをもう一度プレビューしてください。' : '顧客が見つかりません。編集を閉じて一覧を確認してください。',
      409: conflictGuidance(endpoint.path, endpoint.method, code),
      413: 'CSVファイルは10MiB以下にしてください。ファイルを分けて、もう一度プレビューしてください。',
      429: invitation ? '操作が続いたため、一時的に利用できません。時間をおいて、もう一度お試しください。'
        : 'ログイン試行が続いたため、一時的に利用できません。時間をおいて、もう一度お試しください。',
    };
    const importMessages: Record<string, string> = {
      IMPORT_EXPIRED: 'プレビューの有効期限が切れました。CSVをもう一度プレビューしてください。',
      IMPORT_INVALID: 'エラーがあるため1件も登録できません。元のCSVを修正して、もう一度プレビューしてください。',
      IMPORT_REFERENCE_CONFLICT: 'プレビュー後に顧客情報や登録内容が変わりました。CSVを修正して、もう一度プレビューしてください。',
      IMPORT_ALREADY_CONFIRMED: '同じ内容はすでに取り込まれています。元の取込結果を確認してください。',
      VERSION_CONFLICT: '取込内容が更新されています。取込状態を確認してください。',
    };
    const details = typeof failure === 'object' && failure !== null && 'errors' in failure && Array.isArray(failure.errors)
      ? failure.errors.filter((item): item is components['schemas']['FieldError'] => typeof item === 'object' && item !== null && typeof item.field === 'string' && typeof item.message === 'string') : [];
    const invoiceMessages: Record<string, string> = {
      VERSION_CONFLICT: '他の担当者が更新しました。最新内容を取得し、入力中の内容と比較してください。',
      INVOICE_NOT_ACTIVE: 'この請求は取消済みです。最新内容と履歴を確認してください。',
      INVOICE_ALLOCATED: '消込がある請求は訂正・取消できません。先に対象の消込を取り消してください。',
      NOT_FOUND: '請求が見つかりません。請求IDと一覧を確認してください。',
    };
    const invoiceMessage = invoicing ? (code && Object.hasOwn(invoiceMessages, code) ? invoiceMessages[code] : undefined) ?? (response.status === 400 ? '顧客・日付・正の円整数の金額・理由を確認してください。' : response.status === 404 ? invoiceMessages.NOT_FOUND : undefined) : undefined;
    const importMessage = importing && code && Object.hasOwn(importMessages, code) ? importMessages[code] : undefined;
    const statusMessage = Object.hasOwn(messages, response.status) ? messages[response.status] : undefined;
    const aliasMessage = aliasing ? code === 'PAYER_ALIAS_EXISTS' ? '同じ名義と顧客の有効な対応は登録済みです。一覧で対応IDを確認してください。'
      : response.status === 409 ? '他の担当者が変更したか、すでに無効化されています。最新の詳細を取得して確認してください。'
        : response.status === 404 ? '対応または顧客が見つかりません。対応IDと顧客を確認してください。'
          : response.status === 400 ? '名義は200文字以内、無効化理由は500文字以内で入力してください。空白だけの入力や制御文字は使えません。' : undefined : undefined;
    const allocationMessage = allocating ? response.status === 400 ? '現金・明示した手数料・理由を確認してください。' : response.status === 404 ? '入金または請求が見つかりません。最新内容を確認してください。' : undefined : undefined;
    const collectionMessage = collecting ? response.status === 409 ? code === 'COLLECTION_NOT_ELIGIBLE' ? '期日超過の未回収がないため文面の対象外です。最新の対応状況を確認してください。' : '対応状況が変更されています。最新内容と入力中の内容を比較してください。' : response.status === 404 ? '請求または対応履歴が見つかりません。' : response.status === 400 ? '基準日・次回対応日・メモや理由の入力内容を確認してください。' : undefined : undefined;
    throw new ApiError(response.status, collectionMessage ?? allocationMessage ?? aliasMessage ?? invoiceMessage ?? importMessage ?? statusMessage ?? '処理に失敗しました。時間をおいて、もう一度お試しください。', code, details);
  }
  return response;
}
