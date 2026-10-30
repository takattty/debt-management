import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, ApiError, type Customer, type Receivable, type ReceivablesQuery, type Session } from './api';

import InvoicePanel from './InvoicePanel';
import { CollectionDetail, japanBusinessDate } from './CollectionsPanel';

const yen = new Intl.NumberFormat('ja-JP', { style: 'currency', currency: 'JPY', maximumFractionDigits: 0 });
const message = (failure: unknown) => failure instanceof Error ? failure.message : '債権一覧を取得できませんでした。もう一度お試しください。';

export default function ReceivablesPanel({ customers, session, onBusyChange, onSessionExpired }: { customers: Customer[]; session: Session; onBusyChange: (busy: boolean) => void; onSessionExpired: (failure: ApiError) => void }) {
  const [selectedId, setSelectedId] = useState('');
  const [collectionId, setCollectionId] = useState('');
  const [lookupId, setLookupId] = useState('');
  const [items, setItems] = useState<Receivable[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [status, setStatus] = useState<NonNullable<ReceivablesQuery['status']>>('ACTIVE');
  const [customerId, setCustomerId] = useState('');
  const [dueFrom, setDueFrom] = useState('');
  const [dueTo, setDueTo] = useState('');
  const [activeQuery, setActiveQuery] = useState<ReceivablesQuery>({ limit: 50, status: 'ACTIVE' });
  const [pageNumber, setPageNumber] = useState(1);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const failureHandler = useRef(onSessionExpired);
  useEffect(() => { failureHandler.current = onSessionExpired; }, [onSessionExpired]);

  useEffect(() => {
    let active = true;
    onBusyChange(true);
    api.listReceivables({ limit: 50, status: 'ACTIVE' }).then((page) => {
      if (active) { setItems(page.items); setNextCursor(page.nextCursor); }
    }).catch((failure: unknown) => {
      if (!active) return;
      if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) failureHandler.current(failure);
      else setError(message(failure));
    }).finally(() => { if (active) { setBusy(false); onBusyChange(false); } });
    return () => { active = false; onBusyChange(false); };
  }, [onBusyChange]);

  async function load(query: ReceivablesQuery, number = 1) {
    setBusy(true); onBusyChange(true); setError(''); setItems([]); setNextCursor(null);
    try {
      const page = await api.listReceivables(query);
      setItems(page.items); setNextCursor(page.nextCursor); setPageNumber(number);
    } catch (failure) {
      if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) failureHandler.current(failure);
      else setError(message(failure));
    } finally { setBusy(false); onBusyChange(false); }
  }

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (dueFrom && dueTo && dueFrom > dueTo) { setError('支払期日の開始日は終了日以前にしてください。'); return; }
    const query: ReceivablesQuery = { limit: 50, status, ...(customerId ? { customerId } : {}), ...(dueFrom ? { dueFrom } : {}), ...(dueTo ? { dueTo } : {}) };
    setActiveQuery(query); await load(query);
  }

  return <section className="panel" aria-labelledby="receivables-title">
    <div className="panel-heading"><span className="step-number">一覧</span><div><h2 id="receivables-title">登録済みの債権</h2><p>有効な請求を初期表示します。状態を切り替えると取消済みの請求と原本履歴も探せます。支払期日順に表示します。</p></div></div>
    <form onSubmit={(event) => { event.preventDefault(); setSelectedId(lookupId.trim()); }}><label htmlFor="invoice-lookup">請求IDで訂正・履歴を開く（取消済みも参照可）<input id="invoice-lookup" required value={lookupId} disabled={busy} onChange={(event) => setLookupId(event.target.value)} /></label><button type="submit" className="secondary-button" disabled={busy}>請求を開く</button></form>
    {selectedId && <InvoicePanel key={selectedId} id={selectedId} customers={customers} session={session} onBusyChange={(value) => { setBusy(value); onBusyChange(value); }} onSessionExpired={onSessionExpired} onChanged={() => load(activeQuery)} onClose={() => setSelectedId('')} />}
    {collectionId && <CollectionDetail key={collectionId} id={collectionId} asOf={japanBusinessDate()} customers={customers} session={session} onBusyChange={onBusyChange} onSessionExpired={onSessionExpired} onClose={() => setCollectionId('')} />}
    <form className="receivables-filters" onSubmit={search}>
      <div><label htmlFor="receivable-status">請求の状態</label><select id="receivable-status" value={status} onChange={(event) => setStatus(event.target.value as NonNullable<ReceivablesQuery['status']>)} disabled={busy}><option value="ACTIVE">有効な請求</option><option value="CANCELLED">取消済み</option><option value="ALL">すべての状態</option></select></div>
      <div><label htmlFor="receivable-customer">顧客で絞り込む</label><select id="receivable-customer" value={customerId} onChange={(event) => setCustomerId(event.target.value)} disabled={busy}><option value="">すべての顧客</option>{customers.map((customer) => <option key={customer.id} value={customer.id}>{customer.code} — {customer.name}</option>)}</select></div>
      <label htmlFor="receivable-due-from">支払期日（開始）<input id="receivable-due-from" type="date" value={dueFrom} onChange={(event) => setDueFrom(event.target.value)} disabled={busy} /></label>
      <label htmlFor="receivable-due-to">支払期日（終了）<input id="receivable-due-to" type="date" value={dueTo} onChange={(event) => setDueTo(event.target.value)} disabled={busy} /></label>
      <button className="primary-button" disabled={busy}>条件で表示</button>
    </form>
    {error && <p className="error" role="alert">{error}</p>}
    {busy ? <p role="status">債権一覧を読み込んでいます…</p> : items.length === 0 ? !error && <p className="import-guide">表示する請求はありません。状態・顧客・支払期日の条件を確認してください。</p> : <>
      <div className="table-scroll"><table aria-label="債権一覧"><thead><tr><th scope="col">請求書番号</th><th scope="col">状態</th><th scope="col">顧客</th><th scope="col">請求日</th><th scope="col">支払期日</th><th scope="col">請求額</th><th scope="col">未回収額</th><th scope="col">操作</th></tr></thead><tbody>{items.map((item) => <tr key={item.id}><td>{item.invoiceNumber}</td><td>{item.status === 'CANCELLED' ? '取消済み' : '有効'}</td><td>{item.customerCode}<br />{item.customerName}</td><td>{item.issueDate}</td><td>{item.dueDate}</td><td>{yen.format(item.amount)}</td><td>{yen.format(item.outstandingAmount)}</td><td><button type="button" className="text-button" disabled={busy} aria-label={`請求 ${item.invoiceNumber} の${item.status === 'CANCELLED' ? '履歴' : '訂正・履歴'}`} onClick={() => { setSelectedId(item.id); setLookupId(item.id); }}>{item.status === 'CANCELLED' ? '履歴を確認' : '訂正・履歴'}</button><button type="button" className="text-button" disabled={busy} aria-label={`請求 ${item.invoiceNumber} の対応・履歴`} onClick={() => setCollectionId(item.id)}>対応・履歴</button></td></tr>)}</tbody></table></div>
      <p className="import-guide">{pageNumber}ページ目・{items.length}件を表示しています。金額・未回収額は登録内容から取得した値です。</p>
    </>}
    <div className="button-row"><button type="button" className="secondary-button" disabled={busy} onClick={() => load(activeQuery)}>最初のページを再取得</button><button type="button" className="secondary-button" disabled={busy || !nextCursor} onClick={() => load({ ...activeQuery, cursor: nextCursor ?? undefined }, pageNumber + 1)}>次の50件</button></div>
  </section>;
}
