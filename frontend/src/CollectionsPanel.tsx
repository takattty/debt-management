import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, ApiError, type CollectionCase, type CollectionHistory, type CollectionsQuery, type Customer, type Session } from './api';
import InvoicePanel from './InvoicePanel';
import { formatJstTimestamp } from './timestamp-presentation';

type SharedProps = { customers: Customer[]; session: Session; onBusyChange: (busy: boolean) => void; onSessionExpired: (failure: ApiError) => void };
const yen = new Intl.NumberFormat('ja-JP', { style: 'currency', currency: 'JPY', maximumFractionDigits: 0 });
const failureMessage = (failure: unknown) => failure instanceof Error ? failure.message : '対応状況を取得できませんでした。';
export function japanBusinessDate(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now);
  return ['year', 'month', 'day'].map((type) => parts.find((part) => part.type === type)?.value).join('-');
}
const caseStatus = (item: CollectionCase) => item.invoiceStatus === 'CANCELLED' ? '取消済み・対象外' : !item.eligible ? '期日超過の未回収なし・対象外' : item.held ? '保留中' : '対応対象';
const kindName: Record<CollectionHistory['kind'], string> = { NOTE: '対応メモ', HOLD: '保留', RELEASE: '保留解除' };

export default function CollectionsPanel(props: SharedProps) {
  const [asOf, setAsOf] = useState(japanBusinessDate);
  const [view, setView] = useState<NonNullable<CollectionsQuery['view']>>('ACTIONABLE');
  const [query, setQuery] = useState<CollectionsQuery>({ asOf, view, limit: 50 });
  const [items, setItems] = useState<CollectionCase[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const alive = useRef(true);
  const handlers = useRef(props);
  useEffect(() => { handlers.current = props; });
  useEffect(() => { alive.current = true; return () => { alive.current = false; handlers.current.onBusyChange(false); }; }, []);

  async function load(search: CollectionsQuery, number = 1) {
    setBusy(true); props.onBusyChange(true); setError(''); setItems([]); setNext(null);
    try {
      const response = await api.listCollections(search);
      if (!alive.current) return;
      setItems(response.items); setNext(response.nextCursor);
      setPage(number);
    } catch (failure) {
      if (!alive.current) return;
      if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) handlers.current.onSessionExpired(failure);
      else setError(failureMessage(failure));
    } finally { if (alive.current) { setBusy(false); handlers.current.onBusyChange(false); } }
  }

  function search(event: FormEvent) {
    event.preventDefault(); setSelected('');
    const searchQuery: CollectionsQuery = { asOf, view, limit: 50 };
    setQuery(searchQuery); void load(searchQuery);
  }

  return <section className="panel" aria-labelledby="collections-title">
    <h2 id="collections-title">督促の対応候補</h2>
    <p>日本の業務日を基準に、支払期日を過ぎた未回収の請求を確認します。期日当日は含まず、翌日が超過1日目です。保留は自動解除されません。</p>
    <form onSubmit={search} className="receivables-filters">
      <label htmlFor="collection-asof">基準日（日本の業務日）<input id="collection-asof" type="date" required value={asOf} disabled={busy} onChange={(event) => setAsOf(event.target.value)} /></label>
      <label htmlFor="collection-view">対応の状態<select id="collection-view" value={view} disabled={busy} onChange={(event) => setView(event.target.value as NonNullable<CollectionsQuery['view']>)}><option value="ACTIONABLE">対応対象（保留を除く）</option><option value="HELD">保留中</option><option value="ALL">すべて（保留を含む）</option></select></label>
      <button className="primary-button" disabled={busy}>候補を表示</button>
    </form>
    <p className="helper">完済・取消済みの過去の対応は、債権一覧で「すべての状態」を選び、各請求の「対応・履歴」から確認できます。</p>
    {busy && <p role="status">候補を取得しています…</p>}
    {error && <p role="alert" className="error">{error}</p>}
    {!busy && <><p>表示基準日：{query.asOf}（日本の業務日）</p><div className="table-scroll"><table aria-label="督促候補一覧"><thead><tr><th>請求書番号</th><th>顧客</th><th>支払期日・超過日数</th><th>現在の未回収額</th><th>状態・次回対応日</th><th>操作</th></tr></thead><tbody>{items.map((item) => <tr key={item.invoiceId}><td>{item.invoiceNumber}</td><td>{item.customerCode}<br />{item.customerName}</td><td>{item.dueDate}<br />{item.daysOverdue}日</td><td>{yen.format(item.outstandingAmount)}</td><td>{caseStatus(item)}<br />次回：{item.nextActionDate ?? '未設定'}</td><td><button type="button" className="text-button" onClick={() => setSelected(item.invoiceId)} aria-label={`請求 ${item.invoiceNumber} の対応・履歴`}>対応・履歴</button></td></tr>)}</tbody></table></div><p>{page}ページ目・{items.length}件</p>{items.length === 0 && <p>候補はありません。基準日と条件を確認してください。</p>}<button type="button" className="secondary-button" disabled={!next} onClick={() => { setSelected(''); void load({ ...query, cursor: next ?? undefined }, page + 1); }}>次の50件</button></>}
    {selected && <CollectionDetail key={`${selected}:${query.asOf}`} {...props} onBusyChange={(value) => { setBusy(value); props.onBusyChange(value); }} id={selected} asOf={query.asOf} onClose={() => setSelected('')} />}
  </section>;
}

export function CollectionDetail({ id, asOf, onClose, ...props }: SharedProps & { id: string; asOf: string; onClose: () => void }) {
  const [item, setItem] = useState<CollectionCase | null>(null);
  const [history, setHistory] = useState<CollectionHistory[]>([]);
  const [next, setNext] = useState<number | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const [showInvoice, setShowInvoice] = useState(false);
  const handlers = useRef(props);
  useEffect(() => { handlers.current = props; });
  useEffect(() => {
    let active = true; handlers.current.onBusyChange(true);
    Promise.all([api.getCollection(id, asOf), api.listCollectionHistory(id)]).then(([current, events]) => {
      if (active) { setItem(current); setHistory(events.items); setNext(events.nextCursor); }
    }).catch((failure: unknown) => {
      if (!active) return;
      if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) handlers.current.onSessionExpired(failure);
      else setError(failureMessage(failure));
    }).finally(() => { if (active) { setBusy(false); handlers.current.onBusyChange(false); } });
    return () => { active = false; handlers.current.onBusyChange(false); };
  }, [id, asOf]);

  async function moreHistory() {
    if (next === null) return;
    setBusy(true); props.onBusyChange(true); setError('');
    try { const response = await api.listCollectionHistory(id, next); setHistory((current) => [...current, ...response.items]); setNext(response.nextCursor); }
    catch (failure) { if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) props.onSessionExpired(failure); else setError(failureMessage(failure)); }
    finally { setBusy(false); props.onBusyChange(false); }
  }

  async function refresh() {
    const [current, events] = await Promise.all([api.getCollection(id, asOf), api.listCollectionHistory(id)]);
    setItem(current); setHistory(events.items); setNext(events.nextCursor);
  }

  return <section className="panel" aria-label="請求の対応・履歴">
    <div className="button-row"><h2>請求の対応・履歴</h2><button type="button" disabled={busy} onClick={onClose}>対応を閉じる</button></div>
    {busy && <p role="status">対応状況を取得しています…</p>}{error && <p role="alert" className="error">{error}</p>}
    {item && <><h3>{item.invoiceNumber} — {item.customerName}</h3><p>基準日：{item.asOf}（日本の業務日）／支払期日：{item.dueDate}／超過{item.daysOverdue}日</p><p>状態：{caseStatus(item)}／現在の未回収額：{yen.format(item.outstandingAmount)}</p><p>原請求額：{yen.format(item.amount)}／入金充当：{yen.format(item.cashAllocated)}／明示した手数料：{yen.format(item.explicitFees)}</p><p>次回対応日：{item.nextActionDate ?? '未設定'}／保留理由：{item.holdReason ?? 'なし'}</p><p style={{ whiteSpace: 'pre-wrap' }}>最新メモ：{item.latestNote ?? 'なし'}</p>{!item.eligible && <p>現在は候補の対象外です。過去の対応原本は引き続き確認できます。</p>}<button type="button" className="secondary-button" disabled={busy} onClick={() => setShowInvoice(!showInvoice)}>{showInvoice ? '原請求を閉じる' : '原請求・訂正履歴を確認'}</button>
      {showInvoice && <InvoicePanel id={id} {...props} onBusyChange={(value) => { setBusy(value); props.onBusyChange(value); }} onChanged={async () => { await refresh(); setShowInvoice(false); }} onClose={() => setShowInvoice(false)} />}
      <h3>対応の原本履歴</h3><p>担当者は記録時点の表示です。過去のメモ・理由・次回対応日を保持しています。</p>{history.length === 0 && <p>対応履歴はありません。</p>}
      <ol>{history.map((event) => <li key={event.version}><strong>第{event.version}版：{kindName[event.kind]}</strong><p>{event.actorLabel} ／ {formatJstTimestamp(event.occurredAt)}</p><p style={{ whiteSpace: 'pre-wrap' }}>メモ：{event.note ?? 'なし'}／理由：{event.reason ?? 'なし'}</p><p>記録後：{event.held ? '保留中' : '保留なし'}／保留理由：{event.holdReason ?? 'なし'}／次回対応日：{event.nextActionDate ?? '未設定'}</p><details><summary>監査用の担当者ID</summary>{event.actorUserId}</details></li>)}</ol>
      {next !== null && <button type="button" disabled={busy} onClick={() => void moreHistory()}>次の50件の対応履歴</button>}
    </>}
  </section>;
}
