import type { components, operations, paths } from './generated/schema';

export type Customer = components['schemas']['CustomerResponse'];
export type Credentials = { username: string; password: string; csrfToken?: string; csrfHeaderName?: string };
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
  getSession: (credentials: Credentials) => request({ path: '/api/session', method: 'GET' }, credentials),
  listCustomers: (credentials: Credentials) => request({ path: '/api/customers', method: 'GET' }, credentials),
  createCustomer: (credentials: Credentials, body: CustomerCreateInput) => request(
    { path: '/api/customers', method: 'POST' }, credentials, body,
  ),
  logout: (credentials: Credentials) => request({ path: '/api/logout', method: 'POST' }, credentials),
};

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

async function request<E extends Endpoint>(endpoint: E, credentials: Credentials, ...body: RequestBody<E>): Promise<SuccessOf<OperationOf<E>>> {
  let response: Response;
  try {
    response = await fetch(endpoint.path, {
      method: endpoint.method,
      body: body.length ? JSON.stringify(body[0]) : undefined,
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        'X-Requested-With': 'XMLHttpRequest',
        Authorization: `Basic ${btoa(`${credentials.username}:${credentials.password}`)}`,
        ...(credentials.csrfToken && credentials.csrfHeaderName ? { [credentials.csrfHeaderName]: credentials.csrfToken } : {}),
      },
    });
  } catch {
    throw new ApiError(0, 'サーバーに接続できません。接続を確認して、もう一度お試しください。');
  }
  if (!response.ok) {
    const messages: Record<number, string> = {
      400: '入力内容を確認してください。顧客コードと顧客名は必須です。',
      401: 'ユーザー名またはパスワードを確認してください。',
      403: 'この操作を行う権限がありません。管理者にご確認ください。',
      409: 'この顧客コードは登録済みです。別のコードを入力してください。',
    };
    throw new ApiError(response.status, messages[response.status] ?? '処理に失敗しました。時間をおいて、もう一度お試しください。');
  }
  if (response.status === 204) return undefined as SuccessOf<OperationOf<E>>;
  return response.json() as Promise<SuccessOf<OperationOf<E>>>;
}
