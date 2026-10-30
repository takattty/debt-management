import { useState } from 'react';
import { api, ApiError, type PaymentCandidate, type PaymentCandidates, type Session, type AllocationConfirmation } from './api';
import { parseYen, validReason, validVersion } from './allocation-input';

type Props = { paymentId: string; externallyBusy?: boolean; selected: PaymentCandidate[]; session: Session; onBusyChange: (busy: boolean) => void; onSessionExpired: (failure: ApiError) => void; onConfirmed: () => Promise<void> };
type Prepared = { page: PaymentCandidates; items: PaymentCandidate[] };
export default function AllocationConfirmation({ paymentId, externallyBusy = false, selected, session, onBusyChange, onSessionExpired, onConfirmed }: Props) {
  const [prepared, setPrepared] = useState<Prepared | null>(null);
  const [amounts, setAmounts] = useState<Record<string, { cash: string; fee: string }>>({});
  const [reason, setReason] = useState('');
  const [reviewed, setReviewed] = useState(false);
  const [pending, setPending] = useState<AllocationConfirmation | null>(null);
  const [busy, setBusy] = useState(false);
  const [blocked, setBlocked] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  function working(value: boolean) { setBusy(value); onBusyChange(value); }
  function report(failure: unknown) {
    if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) onSessionExpired(failure);
    else setError(failure instanceof Error ? failure.message : '消込を処理できませんでした。');
  }
  async function prepare() {
    working(true); setError(''); setPrepared(null); setReviewed(false); setBlocked(false); setNotice('');
    try {
      const wanted = new Set(selected.map((item) => item.invoiceId)); const found: PaymentCandidate[] = []; const cursors = new Set<string>();
      let first: PaymentCandidates | null = null; let cursor: string | undefined;
      do {
        const page = await api.listPaymentCandidates(paymentId, cursor);
        first ??= page;
        if (page.expectedPaymentVersion !== first.expectedPaymentVersion || page.unallocatedAmount !== first.unallocatedAmount) throw new Error('再取得中に入金残高が変わりました。もう一度内容を再取得してください。');
        for (const item of page.items) if (wanted.delete(item.invoiceId)) found.push(item);
        cursor = page.nextCursor ?? undefined;
        if (cursor && cursors.has(cursor)) throw new Error('候補の続きが取得できません。最新候補から選び直してください。');
        if (cursor) cursors.add(cursor);
      } while (wanted.size > 0 && cursor);
      if (!first || wanted.size > 0) throw new Error('選択した請求が候補から変わりました。最新候補から選び直してください。');
      if (!Number.isSafeInteger(first.unallocatedAmount) || first.unallocatedAmount < 0 || found.some((item) => !Number.isSafeInteger(item.outstandingAmount) || item.outstandingAmount <= 0 || !Number.isSafeInteger(item.amount) || item.amount <= 0)) throw new Error('円整数の残高を確認できません。最新候補から選び直してください。');
      if (!validVersion(first.expectedPaymentVersion) || found.some((item) => !validVersion(item.expectedInvoiceVersion))) throw new Error('versionを確認できません。最新候補から選び直してください。');
      setPrepared({ page: first, items: found }); setAmounts(Object.fromEntries(found.map((item) => [item.invoiceId, { cash: '', fee: '0' }])));
    } catch (failure) { report(failure); }
    finally { working(false); }
  }
  function inputError() {
    if (!prepared || prepared.items.length === 0) return '内容を再取得してください。';
    let total = 0n;
    for (const item of prepared.items) {
      const input = amounts[item.invoiceId]; const cash = parseYen(input?.cash ?? ''); const fee = parseYen(input?.fee ?? '');
      if (cash === null || fee === null) return '現金と手数料は0〜9,007,199,254,740,991の円整数で入力してください。';
      if (cash + fee <= 0n || cash + fee > BigInt(item.outstandingAmount)) return `${item.invoiceNumber} の現金と手数料の合計は、正の金額で現在未回収額以下にしてください。`;
      total += cash;
    }
    if (total <= 0n || total > BigInt(prepared.page.unallocatedAmount)) return '現金の合計は、正の金額で未充当現金以下にしてください。';
    if (!validReason(reason)) return '理由は1〜1000文字で、改行・制御文字を含めず入力してください。';
    if (!reviewed) return '顧客・請求・現金・手数料を確認してください。';
    return '';
  }
  async function send(body: AllocationConfirmation) {
    working(true); setError(''); setNotice(''); setPending(body); let uncertain = false;
    try {
      const result = await api.confirmAllocation(session, paymentId, body); setPending(null); setDone(true);
      setNotice(`消込を確定しました。操作ID：${result.id} / request ID：${result.requestId}`);
      try { await onConfirmed(); } catch (failure) { report(failure); }
    } catch (failure) {
      if (failure instanceof ApiError && (failure.status === 0 || failure.status >= 500)) { uncertain = true; setError('確定結果が不明です。入力を変更せず、同じ操作IDで結果を確認してください。'); }
      else { setPending(null); setBlocked(true); report(failure); }
    } finally { setBusy(false); onBusyChange(uncertain); }
  }
  function confirm() {
    const message = inputError(); if (message || !prepared) { setError(message); return; }
    const body: AllocationConfirmation = { requestId: crypto.randomUUID(), expectedPaymentVersion: prepared.page.expectedPaymentVersion, reason, lines: prepared.items.map((item) => ({ invoiceId: item.invoiceId, expectedInvoiceVersion: item.expectedInvoiceVersion, cashAmount: Number(parseYen(amounts[item.invoiceId].cash)), feeAmount: Number(parseYen(amounts[item.invoiceId].fee)) })) };
    void send(body);
  }
  const locked = busy || externallyBusy || pending !== null || blocked || done;
  return <section aria-label="消込内容の確認"><h3>現金と手数料を指定して確定</h3>
    <p>選択内容を再取得してから金額を入力します。一部入金・複数請求への分割・余剰金の未充当保持に対応します。手数料は自動補完しません。後で変更するときは操作全体の取消と再確定が必要です。</p>
    {!pending && !done && <button type="button" className="secondary-button" disabled={busy || externallyBusy} onClick={prepare}>選択内容を再取得して確認</button>}
    {prepared && <><p>確認時の未充当現金：{prepared.page.unallocatedAmount.toLocaleString('ja-JP')}円 / version {prepared.page.expectedPaymentVersion}</p>
      {prepared.items.map((item) => <fieldset key={item.invoiceId} disabled={locked}><legend>{item.customerCode} / {item.customerName} / {item.invoiceNumber}</legend><p>請求原額 {item.amount.toLocaleString('ja-JP')}円 / 現在未回収 {item.outstandingAmount.toLocaleString('ja-JP')}円 / version {item.expectedInvoiceVersion}</p>
        <label>現金（円） {item.invoiceNumber}<input inputMode="numeric" value={amounts[item.invoiceId]?.cash ?? ''} onChange={(event) => { setAmounts({ ...amounts, [item.invoiceId]: { ...amounts[item.invoiceId], cash: event.target.value } }); setReviewed(false); }} /></label>
        <label>手数料（円） {item.invoiceNumber}<input inputMode="numeric" value={amounts[item.invoiceId]?.fee ?? '0'} onChange={(event) => { setAmounts({ ...amounts, [item.invoiceId]: { ...amounts[item.invoiceId], fee: event.target.value } }); setReviewed(false); }} /></label>
      </fieldset>)}
      <label>消込理由<input value={reason} disabled={locked} onChange={(event) => { setReason(event.target.value); setReviewed(false); }} /></label>
      <label><input type="checkbox" disabled={locked} checked={reviewed} onChange={(event) => setReviewed(event.target.checked)} />顧客・請求・現金額・明示した手数料を確認しました</label>
      <button type="button" className="primary-button" disabled={locked} onClick={confirm}>この内容で消込を確定</button>
    </>}
    {pending && <><p>結果未確認の request ID：{pending.requestId}。このIDと送信内容を保持しています。画面を閉じる前に結果を確認してください。</p><button type="button" className="secondary-button" disabled={busy} onClick={() => send(pending)}>同じ操作IDで結果を確認</button></>}
    {blocked && <p>送信を停止しました。最新内容を再取得し、変更内容を見直してください。</p>}
    {error && <p role="alert" className="error">{error}</p>}{notice && <p role="status" className="success">{notice}</p>}
  </section>;
}
