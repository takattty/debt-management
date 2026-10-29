import type { components, operations, paths } from './generated/schema';

export type Customer = components['schemas']['CustomerResponse'];
export type Session = components['schemas']['SessionResponse'];
export type InvitationRole = components['schemas']['InvitationIssueRequest']['role'];
export type InvitationInspection = components['schemas']['InvitationInspectionResponse'];
export type InvitationIssued = components['schemas']['InvitationIssuedResponse'];
export type ImportBatch = components['schemas']['ImportBatchResponse'];
export type ImportRow = components['schemas']['ImportRowResponse'];
export type ImportError = components['schemas']['ImportErrorResponse'];
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
  getCsrf: () => request({ path: '/api/csrf', method: 'GET' }, undefined),
  login: (body: LoginInput, csrf: Csrf) => request({ path: '/api/auth/login', method: 'POST' }, csrf, body),
  getSession: () => request({ path: '/api/session', method: 'GET' }, undefined),
  listCustomers: () => request({ path: '/api/customers', method: 'GET' }, undefined),
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
  previewCustomerImport: async (csrf: Csrf, file: File): Promise<ImportBatch> => {
    const body = new FormData();
    const field: keyof operations['previewCustomerImport']['requestBody']['content']['multipart/form-data'] = 'file';
    body.append(field, file);
    return readJson<ImportBatch>(await send({ path: '/api/imports/customers/preview', method: 'POST' }, csrf, body));
  },
  getImportBatch: (id: string) => request({ path: '/api/imports/{id}', method: 'GET', url: importUrl(id) }, undefined),
  listImportRows: (id: string, cursor = 0) => request({ path: '/api/imports/{id}/rows', method: 'GET', url: `${importUrl(id)}/rows?cursor=${cursor}&limit=50` }, undefined),
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
    const messages: Record<number, string> = {
      400: endpoint.path === '/api/auth/login' ? 'メールアドレスとパスワードの入力内容を確認してください。'
        : endpoint.path === '/api/invitations' ? '招待先のメールアドレスと権限を確認してください。'
          : invitation ? '入力したメールアドレスとパスワードの条件を確認してください。'
            : importing ? 'CSVファイルと入力形式を確認して、もう一度プレビューしてください。' : '顧客コード・顧客名の形式や文字数を確認してください。',
      401: endpoint.path === '/api/auth/login' ? 'メールアドレスまたはパスワードを確認してください。' : 'ログインの有効期限が切れました。もう一度ログインしてください。',
      403: code === 'ACCESS_DENIED' ? 'この操作を行う権限がありません。管理者にご確認ください。'
        : code === 'CSRF_INVALID' ? 'ログインの有効期限が切れました。もう一度ログインしてください。'
          : '操作を続けられません。画面を再読み込みして、もう一度お試しください。',
      404: importing ? '取込内容を表示できません。CSVをもう一度プレビューしてください。' : '顧客が見つかりません。編集を閉じて一覧を確認してください。',
      409: invitation ? '招待を処理できませんでした。管理者にご確認ください。'
        : importing ? '取込内容が変わっています。状態を確認して、CSVをもう一度プレビューしてください。'
          : code === 'VERSION_CONFLICT' ? '他の担当者が更新しました。最新内容を取得し、入力中の内容と比較してから更新してください。' : 'この顧客コードは登録済みです。別のコードを入力してください。',
      413: 'CSVファイルは10MiB以下にしてください。ファイルを分けて、もう一度プレビューしてください。',
      429: invitation ? '操作が続いたため、一時的に利用できません。時間をおいて、もう一度お試しください。'
        : 'ログイン試行が続いたため、一時的に利用できません。時間をおいて、もう一度お試しください。',
    };
    const importMessages: Record<string, string> = {
      IMPORT_EXPIRED: 'プレビューの有効期限が切れました。CSVをもう一度プレビューしてください。',
      IMPORT_INVALID: 'エラーがあるため1件も登録できません。元のCSVを修正して、もう一度プレビューしてください。',
      IMPORT_REFERENCE_CONFLICT: 'プレビュー後に顧客コードが登録されました。CSVを修正して、もう一度プレビューしてください。',
      IMPORT_ALREADY_CONFIRMED: '同じ内容はすでに取り込まれています。元の取込結果を確認してください。',
      VERSION_CONFLICT: '取込内容が更新されています。取込状態を確認してください。',
    };
    const details = typeof failure === 'object' && failure !== null && 'errors' in failure && Array.isArray(failure.errors)
      ? failure.errors.filter((item): item is components['schemas']['FieldError'] => typeof item === 'object' && item !== null && typeof item.field === 'string' && typeof item.message === 'string') : [];
    throw new ApiError(response.status, (importing && code ? importMessages[code] : undefined) ?? messages[response.status] ?? '処理に失敗しました。時間をおいて、もう一度お試しください。', code, details);
  }
  return response;
}
