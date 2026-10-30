import { useEffect, useRef, useState } from 'react';
import { api, ApiError, type AllocationHistory, type AllocationHistoryPage, type AllocationReversal, type Session } from './api';
import { formatJstTimestamp } from './timestamp-presentation';
import { validReason, validVersion } from './allocation-input';

type Props = { paymentId: string; externallyBusy?: boolean; revision: string; session: Session; onBusyChange: (busy: boolean) => void; onSessionExpired: (failure: ApiError) => void; onChanged: () => Promise<void> };
type Draft = { entry: AllocationHistory; expectedPaymentVersion: string };
type Pending = { operationId: string; body: AllocationReversal };
export default function AllocationHistoryPanel({ paymentId, externallyBusy = false, revision, session, onBusyChange, onSessionExpired, onChanged }: Props) {
  const [history, setHistory] = useState<AllocationHistoryPage | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [reason, setReason] = useState(''); const [reviewed, setReviewed] = useState(false);
  const [busy, setBusy] = useState(true); const [pending, setPending] = useState<Pending | null>(null);
  const [blocked, setBlocked] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('');
  const expired = useRef(onSessionExpired);
  useEffect(() => { expired.current = onSessionExpired; }, [onSessionExpired]);
  function report(failure: unknown) {
    if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) expired.current(failure);
    else setError(failure instanceof Error ? failure.message : '消込履歴を取得できませんでした。');
  }
  useEffect(() => {
    let active = true; setBusy(true); setHistory(null);
    api.listAllocationHistory(paymentId).then((value) => { if (active) setHistory(value); })
      .catch((failure: unknown) => { if (active) report(failure); }).finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, [paymentId, revision]);
  async function load(cursor?: string) {
    setBusy(true); setError(''); setHistory(null);
    try { setHistory(await api.listAllocationHistory(paymentId, cursor)); }
    catch (failure) { report(failure); }
    finally { setBusy(false); }
  }
  async function prepare(id: string) {
    setBusy(true); onBusyChange(true); setError(''); setNotice(''); setDraft(null); setReason(''); setReviewed(false); setBlocked(false);
    try {
      let cursor: string | undefined; let version: string | undefined; const seen = new Set<string>();
      do {
        const page = await api.listAllocationHistory(paymentId, cursor); version ??= page.expectedPaymentVersion;
        if (version !== page.expectedPaymentVersion) throw new Error('再取得中に入金が更新されました。履歴を再取得してください。');
        const entry = page.items.find((item) => item.id === id);
        if (entry) {
          if (entry.kind !== 'CONFIRM' || entry.reversedByOperationId !== null) throw new Error('この消込は取消済みです。履歴を再取得してください。');
          if (!validVersion(version) || entry.lines.length === 0 || entry.lines.some((item) => !validVersion(item.currentInvoiceVersion))) throw new Error('versionを確認できません。履歴を再取得してください。');
          setDraft({ entry, expectedPaymentVersion: version }); return;
        }
        cursor = page.nextCursor ?? undefined;
        if (cursor && seen.has(cursor)) throw new Error('履歴の続きが取得できません。履歴を再取得してください。');
        if (cursor) seen.add(cursor);
      } while (cursor);
      throw new Error('取消対象の消込が見つかりません。履歴を再取得してください。');
    } catch (failure) { report(failure); }
    finally { setBusy(false); onBusyChange(false); }
  }
  async function send(value: Pending) {
    setBusy(true); onBusyChange(true); setPending(value); setError(''); setNotice(''); let uncertain = false;
    try {
      const result = await api.reverseAllocation(session, paymentId, value.operationId, value.body); setPending(null); setDraft(null);
      setNotice(`操作全体を取り消しました。取消操作ID：${result.id} / 元操作ID：${result.reversesId}。再確定する場合は最新候補から選び直してください。`);
      try { setHistory(await api.listAllocationHistory(paymentId)); await onChanged(); } catch (failure) { report(failure); }
    } catch (failure) {
      if (failure instanceof ApiError && (failure.status === 0 || failure.status >= 500)) { uncertain = true; setError('取消結果が不明です。同じ操作IDで結果を確認してください。'); }
      else { setPending(null); setBlocked(true); report(failure); }
    } finally { setBusy(false); onBusyChange(uncertain); }
  }
  function reverse() {
    if (!draft) return;
    if (!validReason(reason) || !reviewed) { setError('取消理由（1〜1000文字、改行・制御文字なし）と操作全体の確認が必要です。'); return; }
    void send({ operationId: draft.entry.id, body: { requestId: crypto.randomUUID(), expectedPaymentVersion: draft.expectedPaymentVersion, reason, invoices: draft.entry.lines.map((line) => ({ invoiceId: line.invoiceId, expectedInvoiceVersion: line.currentInvoiceVersion })) } });
  }
  const locked = busy || externallyBusy || pending !== null;
  return <section aria-label="消込履歴"><h3>消込履歴</h3><p>顧客名・請求書番号・連絡先・現在versionは現在の参照情報です。当時の顧客スナップショットではありません。操作ID・実行者UUID・理由・記録時刻・請求ID・当時のversion・現金・手数料は履歴として保持します。</p><p>確定と取消は別の履歴として残ります。取消は元操作の全請求・現金・手数料をまとめて戻します。</p>
    {busy && <p role="status">履歴を確認しています…</p>}{error && <p role="alert" className="error">{error}</p>}{notice && <p role="status" className="success">{notice}</p>}
    {history && <><p>現在の入金version：{history.expectedPaymentVersion}</p>{history.items.length === 0 ? <p>消込履歴はありません。</p> : history.items.map((entry) => <article key={entry.id} aria-label={`操作 ${entry.id}`}>
      <h4>{entry.kind === 'CONFIRM' ? '消込確定' : '操作全体の取消'} / {formatJstTimestamp(entry.occurredAt)}</h4>
      <p>操作ID：{entry.id} / request ID：{entry.requestId} / 記録時入金version：{entry.paymentVersion}</p>
      <p>実行者：{entry.actorEmail} / UUID：{entry.actorUserId}</p><p>理由：{entry.reason}</p>
      {entry.reversesId && <p>取消対象の元操作ID：{entry.reversesId}</p>}{entry.reversedByOperationId && <p>取消済み / 取消操作ID：{entry.reversedByOperationId}</p>}
      <div className="table-scroll"><table aria-label={`操作 ${entry.id} の明細`}><thead><tr><th>現在の顧客</th><th>請求（現在の表示情報）</th><th>現金</th><th>明示手数料</th><th>当時の請求version</th><th>現在の請求version</th></tr></thead><tbody>{entry.lines.map((line) => <tr key={line.invoiceId}><td>{line.customerName}<small>{line.customerId}</small></td><td>{line.invoiceNumber}<small>{line.invoiceId}</small></td><td>{line.cashAmount.toLocaleString('ja-JP')}円</td><td>{line.feeAmount.toLocaleString('ja-JP')}円</td><td>{line.observedInvoiceVersion}</td><td>{line.currentInvoiceVersion}</td></tr>)}</tbody></table></div>
      {entry.kind === 'CONFIRM' && !entry.reversedByOperationId && <button type="button" className="secondary-button" disabled={locked} onClick={() => prepare(entry.id)}>操作 {entry.id} の取消内容を再取得</button>}
    </article>)}<div className="button-row"><button type="button" className="secondary-button" disabled={locked} onClick={() => load()}>履歴の最初を再取得</button><button type="button" className="secondary-button" disabled={locked || !history.nextCursor} onClick={() => load(history.nextCursor ?? undefined)}>次の履歴50件</button></div></>}
    {draft && <section aria-label="操作全体の取消確認"><h4>元操作 {draft.entry.id} を全件取り消す</h4><p>取消前の入金version：{draft.expectedPaymentVersion}</p><ul>{draft.entry.lines.map((line) => <li key={line.invoiceId}>{line.customerName} / {line.invoiceNumber} / 現金 {line.cashAmount.toLocaleString('ja-JP')}円 / 手数料 {line.feeAmount.toLocaleString('ja-JP')}円 / 現在version {line.currentInvoiceVersion}</li>)}</ul>
      <label>取消理由<input value={reason} disabled={locked || blocked} onChange={(event) => { setReason(event.target.value); setReviewed(false); }} /></label>
      <label><input type="checkbox" checked={reviewed} disabled={locked || blocked} onChange={(event) => setReviewed(event.target.checked)} />全請求の現金と手数料をまとめて取り消すことを確認しました</label>
      <button type="button" className="primary-button" disabled={locked || blocked} onClick={reverse}>操作全体を取り消す</button>{blocked && <p>最新履歴から取消内容を再取得し、変更内容を確認してください。</p>}
    </section>}
    {pending && <><p>結果未確認の request ID：{pending.body.requestId}。入力を変更せず結果を確認してください。</p><button type="button" className="secondary-button" disabled={busy} onClick={() => send(pending)}>同じ取消操作IDで結果を確認</button></>}
  </section>;
}
