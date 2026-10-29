export type Customer = { id: string; code: string; name: string; version: number };
export type Credentials = { username: string; password: string; csrfToken?: string; csrfHeaderName?: string };
export type Session = { username: string; csrfToken: string; csrfHeaderName: string };

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

export async function request<T>(path: string, credentials: Credentials, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        'X-Requested-With': 'XMLHttpRequest',
        Authorization: `Basic ${btoa(`${credentials.username}:${credentials.password}`)}`,
        ...(credentials.csrfToken && credentials.csrfHeaderName ? { [credentials.csrfHeaderName]: credentials.csrfToken } : {}),
        ...init?.headers,
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
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
