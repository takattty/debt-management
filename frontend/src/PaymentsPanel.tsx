import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, ApiError, type Payment, type PaymentsQuery } from './api';

const yen = new Intl.NumberFormat('ja-JP', { style: 'currency', currency: 'JPY', maximumFractionDigits: 0 });
type Props = { onBusyChange: (busy: boolean) => void; onSessionExpired: (failure: ApiError) => void };
export default function PaymentsPanel({ onBusyChange, onSessionExpired }: Props) {
  const [items, setItems] = useState<Payment[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [activeQuery, setActiveQuery] = useState<PaymentsQuery>({ limit: 50 });
  const [pageNumber, setPageNumber] = useState(1);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const failureHandler = useRef(onSessionExpired);
  useEffect(() => { failureHandler.current = onSessionExpired; }, [onSessionExpired]);
  function report(failure: unknown) {
    if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) failureHandler.current(failure);
    else setError(failure instanceof Error ? failure.message : '入金一覧を取得できませんでした。接続を確認してください。');
  }
  useEffect(() => {
    let active = true; onBusyChange(true);
    api.listPayments({ limit: 50 }).then((page) => {
      if (active) { setItems(page.items); setNextCursor(page.nextCursor); }
    }).catch((failure: unknown) => { if (active) report(failure); })
      .finally(() => { if (active) { setBusy(false); onBusyChange(false); } });
    return () => { active = false; onBusyChange(false); };
  }, [onBusyChange]);
  async function load(query: PaymentsQuery, number = 1) {
    setBusy(true); onBusyChange(true); setError(''); setItems([]); setNextCursor(null);
    try {
      const page = await api.listPayments(query); setItems(page.items); setNextCursor(page.nextCursor); setPageNumber(number);
    } catch (failure) { report(failure); }
    finally { setBusy(false); onBusyChange(false); }
  }
  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (dateFrom && dateTo && dateFrom > dateTo) { setError('振込日の開始日は終了日以前にしてください。'); return; }
    const query: PaymentsQuery = { limit: 50, ...(dateFrom ? { dateFrom } : {}), ...(dateTo ? { dateTo } : {}) };
    setActiveQuery(query); await load(query);
  }
  return <section className="panel" aria-labelledby="payments-title">
    <div className="panel-heading"><span className="step-number">一覧</span><div><h2 id="payments-title">登録済みの入金</h2><p>確定した受取入金を振込日順に表示します。取引ID・振込日・振込名義・金額は登録後に変更できません。</p></div></div>
    <p className="import-guide">消込機能はまだ利用できません。現在の未充当額は入金額と同額です。表示だけで請求残高が変わることはありません。</p>
    <form className="receivables-filters" onSubmit={search}>
      <label htmlFor="payment-date-from">振込日（開始）<input id="payment-date-from" type="date" value={dateFrom} disabled={busy} onChange={(event) => setDateFrom(event.target.value)} /></label>
      <label htmlFor="payment-date-to">振込日（終了）<input id="payment-date-to" type="date" value={dateTo} disabled={busy} onChange={(event) => setDateTo(event.target.value)} /></label>
      <button className="primary-button" disabled={busy}>条件で表示</button>
    </form>
    {error && <p className="error" role="alert">{error}</p>}
    {busy ? <p role="status">入金一覧を読み込んでいます…</p> : items.length === 0 ? !error && <p className="import-guide">表示する入金はありません。入金CSVを確定するか、振込日の条件を変更してください。</p> : <>
      <div className="table-scroll"><table aria-label="入金一覧"><thead><tr><th scope="col">取引ID</th><th scope="col">振込日</th><th scope="col">振込名義</th><th scope="col">入金額</th><th scope="col">未充当額</th><th scope="col">元の取込ID</th></tr></thead><tbody>{items.map((item) => <tr key={item.id}><td>{item.transactionId}</td><td>{item.transferDate}</td><td>{item.payerName}</td><td>{yen.format(item.amount)}</td><td>{yen.format(item.unallocatedAmount)}</td><td>{item.sourceBatchId}</td></tr>)}</tbody></table></div>
      <p className="import-guide">{pageNumber}ページ目・{items.length}件を表示しています。入金額・未充当額はサーバーから取得した値です。</p>
    </>}
    <div className="button-row"><button type="button" className="secondary-button" disabled={busy} onClick={() => load(activeQuery)}>最初のページを再取得</button><button type="button" className="secondary-button" disabled={busy || !nextCursor} onClick={() => load({ ...activeQuery, cursor: nextCursor ?? undefined }, pageNumber + 1)}>次の50件</button></div>
  </section>;
}
