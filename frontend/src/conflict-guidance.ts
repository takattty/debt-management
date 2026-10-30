// AllocationCommands at 9c045adb defines these codes; ApiError.code is an OpenAPI string.
const allocationMessages: Record<string, string> = {
  VERSION_CONFLICT: '他の担当者が残高を更新しました。最新内容を再取得して確認してください。',
  BALANCE_CONFLICT: '現金または請求の残高が不足しています。最新内容を再取得して確認してください。',
  INVOICE_NOT_ACTIVE: '選択した請求は取消済みです。最新候補から選び直してください。',
  ALLOCATION_ALREADY_REVERSED: 'この消込はすでに取消済みです。履歴を再取得してください。',
  IDEMPOTENCY_CONFLICT: '同じ操作IDに別の内容が指定されました。履歴を確認してください。',
  VERSION_EXHAUSTED: 'versionの上限に達しました。管理者にご確認ください。',
};
const unknownConflict = '現在の状態では処理できません。最新内容を取得して確認してください。';
export function conflictGuidance(path: string, method: string, code?: string): string {
  if (path.startsWith('/api/payments/')) return code && Object.hasOwn(allocationMessages, code) ? allocationMessages[code] : unknownConflict;
  if (path.startsWith('/api/invitations')) return '招待を処理できませんでした。管理者にご確認ください。';
  if (path.startsWith('/api/imports')) return '取込内容が変わっています。状態を確認して、CSVをもう一度プレビューしてください。';
  if (code === 'VERSION_CONFLICT') return '他の担当者が更新しました。最新内容を取得し、入力中の内容と比較してから更新してください。';
  const customerMutation = (path === '/api/customers' && method === 'POST') || (path === '/api/customers/{id}' && method === 'PUT');
  if (customerMutation && code === 'DUPLICATE_CUSTOMER_CODE') return 'この顧客コードは登録済みです。別のコードを入力してください。';
  return unknownConflict;
}
