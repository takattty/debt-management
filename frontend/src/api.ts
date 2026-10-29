import type { components, operations, paths } from './generated/schema';

export type Customer = components['schemas']['CustomerResponse'];
export type Session = components['schemas']['SessionResponse'];
export type InvitationRole = components['schemas']['InvitationIssueRequest']['role'];
export type InvitationInspection = components['schemas']['InvitationInspectionResponse'];
export type InvitationIssued = components['schemas']['InvitationIssuedResponse'];
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
};

export class ApiError extends Error {
  constructor(public status: number, message: string, public code?: components['schemas']['ApiError']['code']) { super(message); }
}

async function request<E extends Endpoint>(endpoint: E, csrf: Csrf | undefined, ...body: RequestBody<E>): Promise<SuccessOf<OperationOf<E>>> {
  let response: Response;
  try {
    response = await fetch(endpoint.path, {
      method: endpoint.method,
      body: body.length ? JSON.stringify(body[0]) : undefined,
      credentials: 'same-origin',
      cache: 'no-store',
      headers: {
        'Content-Type': 'application/json',
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
    const messages: Record<number, string> = {
      400: endpoint.path === '/api/auth/login' ? 'メールアドレスとパスワードの入力内容を確認してください。'
        : endpoint.path === '/api/invitations' ? '招待先のメールアドレスと権限を確認してください。'
          : invitation ? '入力したメールアドレスとパスワードの条件を確認してください。' : '顧客コード・顧客名の形式や文字数を確認してください。',
      401: endpoint.path === '/api/auth/login' ? 'メールアドレスまたはパスワードを確認してください。' : 'ログインの有効期限が切れました。もう一度ログインしてください。',
      403: code === 'ACCESS_DENIED' ? 'この操作を行う権限がありません。管理者にご確認ください。'
        : code === 'CSRF_INVALID' ? 'ログインの有効期限が切れました。もう一度ログインしてください。'
          : '操作を続けられません。画面を再読み込みして、もう一度お試しください。',
      409: invitation ? '招待を処理できませんでした。管理者にご確認ください。' : 'この顧客コードは登録済みです。別のコードを入力してください。',
      429: invitation ? '操作が続いたため、一時的に利用できません。時間をおいて、もう一度お試しください。'
        : 'ログイン試行が続いたため、一時的に利用できません。時間をおいて、もう一度お試しください。',
    };
    throw new ApiError(response.status, messages[response.status] ?? '処理に失敗しました。時間をおいて、もう一度お試しください。', code);
  }
  if (response.status === 204) return undefined as SuccessOf<OperationOf<E>>;
  return response.json() as Promise<SuccessOf<OperationOf<E>>>;
}
