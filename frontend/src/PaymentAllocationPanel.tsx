import { useEffect, useRef, useState } from 'react';
import { api, ApiError, type Payment, type PaymentCandidate, type PaymentCandidates } from './api';

const yen = (value: number) => Number.isSafeInteger(value) && value >= 0 ? `${value.toLocaleString('ja-JP')}円` : '金額を確認できません';
type Props = { payment: Payment; onBusyChange: (busy: boolean) => void; onSessionExpired: (failure: ApiError) => void; onClose: () => void };
export default function PaymentAllocationPanel({ payment, onBusyChange, onSessionExpired, onClose }: Props) {
  const [page, setPage] = useState<PaymentCandidates | null>(null);
  const [selected, setSelected] = useState<PaymentCandidate[]>([]);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const expired = useRef(onSessionExpired);
  useEffect(() => { expired.current = onSessionExpired; }, [onSessionExpired]);
  function report(failure: unknown) {
    if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) expired.current(failure);
    else setError(failure instanceof Error ? failure.message : '消込候補を取得できませんでした。');
  }
  useEffect(() => {
    let active = true; onBusyChange(true);
    api.listPaymentCandidates(payment.id).then((value) => { if (active) setPage(value); })
      .catch((failure: unknown) => { if (active) report(failure); })
      .finally(() => { if (active) { setBusy(false); onBusyChange(false); } });
    return () => { active = false; onBusyChange(false); };
  }, [payment.id, onBusyChange]);
  async function load(cursor?: string) {
    setBusy(true); onBusyChange(true); setError(''); setPage(null);
    if (!cursor) setSelected([]);
    try { setPage(await api.listPaymentCandidates(payment.id, cursor)); }
    catch (failure) { report(failure); }
    finally { setBusy(false); onBusyChange(false); }
  }
  function choose(item: PaymentCandidate) {
    setSelected((values) => values.some((value) => value.invoiceId === item.invoiceId) ? values.filter((value) => value.invoiceId !== item.invoiceId) : [...values, item]);
  }
  return <section className="panel" aria-labelledby="allocation-title">
    <h2 id="allocation-title">入金 {payment.transactionId} の消込</h2>
    <p>入金原本：{payment.transferDate} / {payment.payerName} / {yen(payment.amount)}</p>
    <p>候補は提案です。名義・金額が一致しても自動確定しません。同じ契約企業内なら別顧客の請求も選べます。</p>
    {error && <p role="alert" className="error">{error}</p>}
    {busy && <p role="status">消込候補を取得しています…</p>}
    {page && <>
      <p>現在の未充当現金：{yen(page.unallocatedAmount)} / 入金version：{page.expectedPaymentVersion}</p>
      <p>{page.payerMatchStatus === 'UNREGISTERED' ? '振込名義の対応は未登録です。顧客と請求を確認してください。' : page.payerMatchStatus === 'MULTIPLE_CUSTOMERS' ? `振込名義が${page.matchedCustomerCount}顧客に対応しています。対象を選んでください。` : '振込名義の対応は1顧客です。請求内容を確認してください。'}</p>
      {page.cashCandidateStatus === 'NO_UNALLOCATED_CASH' ? <p>未充当現金がありません。過去の消込を変更する場合は元の操作全体を取り消してください。</p> : <>
        <p>名義対応、金額一致、支払期日順に表示します。不一致や将来期日の請求も含みます。</p>
        <div className="table-scroll"><table aria-label="消込候補"><thead><tr><th>選択</th><th>顧客</th><th>請求書番号</th><th>支払期日</th><th>請求原額</th><th>現在未回収額</th><th>提案理由</th></tr></thead><tbody>{page.items.map((item) => <tr key={item.invoiceId}><td><input type="checkbox" aria-label={`請求 ${item.invoiceNumber} を選択`} checked={selected.some((value) => value.invoiceId === item.invoiceId)} disabled={busy || (selected.length >= 100 && !selected.some((value) => value.invoiceId === item.invoiceId))} onChange={() => choose(item)} /></td><td>{item.customerCode} / {item.customerName}</td><td>{item.invoiceNumber}<small>{item.invoiceId}</small></td><td>{item.dueDate}</td><td>{yen(item.amount)}</td><td>{yen(item.outstandingAmount)}</td><td>{item.manualPayerMatch ? '名義対応あり' : '名義対応なし'} / {item.exactAmountMatch ? '未充当現金と一致' : '金額不一致'}</td></tr>)}</tbody></table></div>
        {page.items.length === 0 && <p>表示する候補はありません。</p>}
        <button type="button" className="secondary-button" disabled={busy || !page.nextCursor} onClick={() => load(page.nextCursor ?? undefined)}>次の候補50件</button>
      </>}
    </>}
    {selected.length > 0 && <section aria-label="選択した請求"><h3>選択した請求（{selected.length}件）</h3><ul>{selected.map((item) => <li key={item.invoiceId}>{item.customerCode} / {item.invoiceNumber} / 未回収 {yen(item.outstandingAmount)} / version {item.expectedInvoiceVersion}<button type="button" disabled={busy} onClick={() => choose(item)}>選択解除 {item.invoiceNumber}</button></li>)}</ul><p>選択だけでは残高は変わりません。</p></section>}
    <div className="button-row"><button type="button" className="secondary-button" disabled={busy} onClick={() => load()}>最新候補を取得して選び直す</button><button type="button" className="text-button" disabled={busy} onClick={onClose}>消込を閉じる</button></div>
  </section>;
}
