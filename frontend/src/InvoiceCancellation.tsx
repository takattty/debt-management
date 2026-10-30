import { useState, type FormEvent } from 'react';
import { api, ApiError, type Invoice, type Session } from './api';
import { isBlankText } from './text-input';

type Props = { invoice: Invoice; session: Session; busy: boolean; onBusyChange: (busy: boolean) => void; onSessionExpired: (failure: ApiError) => void; onUpdated: (invoice: Invoice) => Promise<void>; onUncertain: () => void };
export default function InvoiceCancellation({ invoice, session, busy, onBusyChange, onSessionExpired, onUpdated, onUncertain }: Props) {
  const [reason, setReason] = useState('');
  const [confirmedVersion, setConfirmedVersion] = useState<number | null>(null);
  const confirmed = confirmedVersion === invoice.version;
  const [needsReload, setNeedsReload] = useState(false);
  const [compared, setCompared] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const mutable = invoice.status === 'ACTIVE' && invoice.outstandingAmount === invoice.amount;
  function report(failure: unknown) {
    if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) onSessionExpired(failure);
    else setError(failure instanceof Error ? failure.message : '取消を処理できませんでした。最新内容を確認してください。');
  }
  async function cancel(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || !mutable || !confirmed || needsReload) return;
    if (isBlankText(reason)) { setError('取消理由を入力してください。空白だけでは取り消せません。'); return; }
    if ([...reason].length > 1000) { setError('取消理由は1000文字以内で入力してください。'); return; }
    onBusyChange(true); setError(''); setNotice('');
    try {
      const updated = await api.cancelInvoice(session, invoice.id, { version: invoice.version, reason });
      setNeedsReload(true); setConfirmedVersion(null);
      await onUpdated(updated); setNeedsReload(false); setReason(''); setNotice('請求を取り消しました。未回収額は0円となり、債権一覧から除外されました。請求書番号と履歴は保持されます。');
    } catch (failure) {
      if (!(failure instanceof ApiError) || failure.status === 0 || failure.status >= 500 || failure.status === 404 || failure.status === 409) { setNeedsReload(true); setConfirmedVersion(null); onUncertain(); }
      report(failure);
    } finally { onBusyChange(false); }
  }
  async function reload() {
    onBusyChange(true); setError('');
    try {
      const current = await api.getInvoice(invoice.id); await onUpdated(current);
      setNeedsReload(false); setConfirmedVersion(null); setCompared(true);
      if (current.status === 'CANCELLED') setNotice('この請求は取消済みです。履歴で取消理由と担当者を確認できます。再送しません。');
    } catch (failure) { report(failure); }
    finally { onBusyChange(false); }
  }
  return <section aria-labelledby="invoice-cancel-title">
    <h3 id="invoice-cancel-title">請求を取り消す</h3>
    <p>取消対象：{invoice.invoiceNumber} / {invoice.customerCode} — {invoice.customerName} / 請求額 {invoice.amount.toLocaleString('ja-JP')}円 / 支払期日 {invoice.dueDate} / 版 {invoice.version}</p>
    <p>取り消すと未回収額は0円となり、債権一覧・督促候補から除外されます。原請求と訂正・取消の履歴は保持され、請求書番号は再利用できません。</p>
    {!mutable && <p>取消済み、または消込がある請求は取り消せません。消込がある場合は先に対象消込を取り消してください。</p>}
    {error && <p className="error" role="alert">{error}</p>}{notice && <p className="success" role="status">{notice}</p>}
    {needsReload && <p>結果を確認できないため再送できません。最新内容と履歴を取得し、処理結果を確認してください。入力した理由は保持しています。</p>}
    {compared && mutable && <p>上に取得した最新の請求を表示しています。現在の登録内容と履歴を確認し、取り消す対象と影響を改めて確認してください。</p>}
    <form onSubmit={cancel}>
      <label htmlFor="invoice-cancel-reason">取消理由<input id="invoice-cancel-reason" required value={reason} disabled={busy || !mutable} onChange={(event) => { setReason(event.target.value); setConfirmedVersion(null); }} /></label>
      <label className="confirmation-check"><input type="checkbox" checked={confirmed} disabled={busy || !mutable || needsReload} onChange={(event) => setConfirmedVersion(event.target.checked ? invoice.version : null)} />この請求の最新内容と取消の影響を確認し、取り消す</label>
      <div className="button-row"><button className="primary-button" disabled={busy || !mutable || needsReload || !confirmed || isBlankText(reason)}>請求の取消を確定</button><button type="button" className="secondary-button" disabled={busy} onClick={reload}>取消の最新内容・処理結果を取得</button></div>
    </form>
  </section>;
}
