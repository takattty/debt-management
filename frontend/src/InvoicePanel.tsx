import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, ApiError, type Customer, type Invoice, type InvoiceHistory, type Session } from './api';
import InvoiceCancellation from './InvoiceCancellation';

type Props = { id: string; customers: Customer[]; session: Session; onBusyChange: (busy: boolean) => void; onSessionExpired: (failure: ApiError) => void; onChanged: () => Promise<void>; onClose: () => void };
const yen = (amount: number) => new Intl.NumberFormat('ja-JP', { style: 'currency', currency: 'JPY', maximumFractionDigits: 0 }).format(amount);
const status = (invoice: { status: Invoice['status'] }) => invoice.status === 'ACTIVE' ? '確定済み（有効）' : '取消済み';
const failureMessage = (failure: unknown) => failure instanceof Error ? failure.message : '請求を取得できませんでした。接続を確認してください。';
type Initial = { invoice: Invoice; history: Awaited<ReturnType<typeof api.listInvoiceHistory>> };

export default function InvoicePanel(props: Props) {
  const [initial, setInitial] = useState<Initial | null>(null);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const callbacks = useRef(props);
  useEffect(() => { callbacks.current = props; });
  useEffect(() => {
    let active = true;
    callbacks.current.onBusyChange(true);
    Promise.all([api.getInvoice(props.id), api.listInvoiceHistory(props.id)]).then(([invoice, history]) => {
      if (active) { setInitial({ invoice, history }); setError(''); }
    }).catch((failure: unknown) => {
      if (!active) return;
      if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) callbacks.current.onSessionExpired(failure);
      else setError(failureMessage(failure));
    }).finally(() => { if (active) callbacks.current.onBusyChange(false); });
    return () => { active = false; callbacks.current.onBusyChange(false); };
  }, [props.id, retry]);
  return initial ? <InvoiceEditor {...props} initial={initial} /> : <section className="panel" aria-label="請求の読込">{error ? <><p className="error" role="alert">{error}</p><button type="button" onClick={() => setRetry((value) => value + 1)}>請求を再取得</button><button type="button" onClick={props.onClose}>請求を閉じる</button></> : <p role="status">請求と履歴を読み込んでいます…</p>}</section>;
}

function InvoiceEditor({ initial, ...props }: Props & { initial: Initial }) {
  const [current, setCurrent] = useState(initial.invoice);
  const [history, setHistory] = useState<InvoiceHistory[]>(initial.history.items);
  const [cursor, setCursor] = useState(initial.history.nextCursor);
  const [customerId, setCustomerId] = useState(current.customerId);
  const [issueDate, setIssueDate] = useState(current.issueDate);
  const [dueDate, setDueDate] = useState(current.dueDate);
  const [amount, setAmount] = useState(String(current.amount));
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [needsReload, setNeedsReload] = useState(false);
  const [comparison, setComparison] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const mutable = current.status === 'ACTIVE' && current.outstandingAmount === current.amount;
  const blocked = busy || needsReload || !mutable || (comparison && !acknowledged);
  function working(value: boolean) { setBusy(value); props.onBusyChange(value); }
  function report(failure: unknown) {
    if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) props.onSessionExpired(failure);
    else setError(failureMessage(failure));
  }
  async function reload() {
    working(true); setError('');
    try {
      const invoice = await api.getInvoice(props.id); setCurrent(invoice);
      const page = await api.listInvoiceHistory(props.id); setHistory(page.items); setCursor(page.nextCursor);
      await props.onChanged();
      setNeedsReload(false); setComparison(true); setAcknowledged(false);
    } catch (failure) { report(failure); }
    finally { working(false); }
  }
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (blocked) return;
    if (!customerId || !issueDate || !dueDate || dueDate < issueDate || !/^[1-9]\d*$/.test(amount) || !Number.isSafeInteger(Number(amount)) || !reason.trim()) {
      setError('顧客、請求日以降の支払期日、正の円整数の金額、訂正理由を入力してください。'); return;
    }
    working(true); setError(''); setNotice('');
    try {
      const updated = await api.correctInvoice(props.session, props.id, { version: current.version, customerId, issueDate, dueDate, amount: Number(amount), reason: reason.trim() });
      setCurrent(updated); setReason(''); setComparison(false); setAcknowledged(false);
      setNeedsReload(true);
      const page = await api.listInvoiceHistory(props.id); setHistory(page.items); setCursor(page.nextCursor);
      await props.onChanged(); setNeedsReload(false); setNotice('請求を訂正しました。');
    } catch (failure) {
      if (!(failure instanceof ApiError) || failure.status === 0 || failure.status >= 500 || failure.status === 404 || failure.status === 409) setNeedsReload(true);
      report(failure);
    } finally { working(false); }
  }
  async function moreHistory() {
    if (cursor === null || busy) return;
    working(true); setError('');
    try { const page = await api.listInvoiceHistory(props.id, cursor); setHistory((items) => [...items, ...page.items]); setCursor(page.nextCursor); }
    catch (failure) { report(failure); }
    finally { working(false); }
  }
  return <section className="panel" aria-labelledby="invoice-title">
    <h2 id="invoice-title">請求の訂正・履歴：{current.invoiceNumber}</h2>
    <p>請求ID：<code>{current.id}</code>（取消後もこのIDで履歴を開けます）</p>
    <h3>現在の登録内容</h3>
    <dl className="invoice-facts" aria-label="現在の請求内容"><dt>状態</dt><dd>{status(current)}</dd><dt>顧客</dt><dd>{current.customerCode} — {current.customerName}</dd><dt>請求日</dt><dd>{current.issueDate}</dd><dt>支払期日</dt><dd>{current.dueDate}</dd><dt>請求額</dt><dd>{yen(current.amount)}</dd><dt>未回収額</dt><dd>{yen(current.outstandingAmount)}</dd><dt>版</dt><dd>{current.version}</dd></dl>
    <p>請求書番号は変更できません。訂正できるのは有効で未消込の請求です。消込がある場合は先に対象消込を取り消してください。</p>
    {error && <p className="error" role="alert">{error}</p>}{notice && <p className="success" role="status">{notice}</p>}
    {needsReload && <p>処理結果・最新内容を取得して確認してください。入力中の内容は保持しています。変更は自動再送しません。</p>}
    {comparison && <p className="import-guide">上の「現在の登録内容」が取得した最新内容です。下の入力中の内容と比較し、履歴で処理結果を確認してください。</p>}
    <form onSubmit={save}>
      <fieldset disabled={busy || !mutable}><legend>入力中の訂正内容</legend>
        <label htmlFor="invoice-customer">訂正後の顧客<select id="invoice-customer" required value={customerId} onChange={(event) => { setCustomerId(event.target.value); setAcknowledged(false); }}>{props.customers.map((customer) => <option key={customer.id} value={customer.id}>{customer.code} — {customer.name}</option>)}</select></label>
        <div className="customer-edit-fields"><label htmlFor="invoice-issue">訂正後の請求日<input id="invoice-issue" type="date" required value={issueDate} onChange={(event) => { setIssueDate(event.target.value); setAcknowledged(false); }} /></label><label htmlFor="invoice-due">訂正後の支払期日<input id="invoice-due" type="date" required min={issueDate} value={dueDate} onChange={(event) => { setDueDate(event.target.value); setAcknowledged(false); }} /></label><label htmlFor="invoice-amount">訂正後の請求額（円）<input id="invoice-amount" inputMode="numeric" required value={amount} onChange={(event) => { setAmount(event.target.value); setAcknowledged(false); }} /></label></div>
        <label htmlFor="invoice-reason">訂正理由<input id="invoice-reason" required maxLength={1000} value={reason} onChange={(event) => { setReason(event.target.value); setAcknowledged(false); }} /></label>
        {comparison && <label className="confirmation-check"><input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} />最新の登録内容と履歴を確認し、入力中の内容で訂正する</label>}
      </fieldset>
      <div className="button-row"><button className="primary-button" disabled={blocked}>訂正を保存</button><button type="button" className="secondary-button" disabled={busy} onClick={reload}>最新内容・処理結果を取得</button><button type="button" className="secondary-button" disabled={busy} onClick={props.onClose}>請求を閉じる</button></div>
    </form>
    <InvoiceCancellation invoice={current} session={props.session} busy={busy} onBusyChange={working} onSessionExpired={props.onSessionExpired} onUncertain={() => setNeedsReload(true)} onUpdated={async (invoice) => {
      setCurrent(invoice); setAcknowledged(false); setComparison(true); setNotice('');
      const page = await api.listInvoiceHistory(props.id); setHistory(page.items); setCursor(page.nextCursor);
      await props.onChanged(); setNeedsReload(false);
    }} />
    <h3>請求の全履歴（原本から順に表示）</h3>
    <div className="table-scroll"><table aria-label="請求履歴"><thead><tr><th>操作・版</th><th>記録日時・担当者</th><th>理由</th><th>その時点の請求内容</th><th>元の取込ID</th></tr></thead><tbody>{history.map((entry) => <tr key={entry.revision}><td>{entry.operation === 'CREATED' ? '登録時の原本' : entry.operation === 'CORRECTED' ? '訂正' : '取消'}<br />版 {entry.snapshot.version}</td><td><time dateTime={entry.occurredAt}>{new Date(entry.occurredAt).toLocaleString('ja-JP')}</time><br />担当者ID：{entry.actorUserId}</td><td>{entry.reason ?? 'CSV確定による登録'}</td><td>{entry.snapshot.invoiceNumber}<br />{entry.snapshot.customerCode} — {entry.snapshot.customerName}<br />請求日 {entry.snapshot.issueDate} / 支払期日 {entry.snapshot.dueDate}<br />{yen(entry.snapshot.amount)} / {status(entry.snapshot)}</td><td>{entry.snapshot.sourceBatchId}</td></tr>)}</tbody></table></div>
    <p>{history.length}件の履歴を表示中。{cursor === null ? 'すべての履歴を表示しています。' : '続きの履歴があります。'}</p><button type="button" className="secondary-button" disabled={busy || cursor === null} onClick={moreHistory}>履歴の次の50件</button>
  </section>;
}
