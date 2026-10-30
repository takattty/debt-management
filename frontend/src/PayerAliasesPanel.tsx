import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, ApiError, type Customer, type PayerAlias, type Session } from './api';

type Props = { customers: Customer[]; session: Session; onBusyChange: (busy: boolean) => void; onSessionExpired: (failure: ApiError) => void };
const uncertain = (failure: unknown) => !(failure instanceof ApiError) || failure.status === 0 || failure.status >= 500;
const time = (value: string) => new Date(value).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo' });

export default function PayerAliasesPanel({ customers, session, onBusyChange, onSessionExpired }: Props) {
  const [items, setItems] = useState<PayerAlias[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [payerName, setPayerName] = useState('');
  const [customerId, setCustomerId] = useState('');
  const [lookupId, setLookupId] = useState('');
  const [selected, setSelected] = useState<PayerAlias | null>(null);
  const [pendingRegistration, setPendingRegistration] = useState(false);
  const [registrationChecked, setRegistrationChecked] = useState(false);
  const [matches, setMatches] = useState<PayerAlias[]>([]);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const failureHandler = useRef(onSessionExpired);
  useEffect(() => { failureHandler.current = onSessionExpired; }, [onSessionExpired]);
  function report(failure: unknown) {
    if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) failureHandler.current(failure);
    else setError(failure instanceof Error ? failure.message : '対応を取得できませんでした。接続を確認してください。');
  }
  function working(value: boolean) { if (value) { setError(''); setNotice(''); } setBusy(value); onBusyChange(value); }
  useEffect(() => {
    let active = true; onBusyChange(true);
    api.listPayerAliases().then((page) => { if (active) { setItems(page.items); setCursor(page.nextCursor); } })
      .catch((failure: unknown) => { if (active) report(failure); })
      .finally(() => { if (active) { setBusy(false); onBusyChange(false); } });
    return () => { active = false; onBusyChange(false); };
  }, [onBusyChange]);
  const customerLabel = (id: string) => {
    const customer = customers.find((item) => item.id === id); return customer ? `${customer.code} — ${customer.name}` : `顧客ID：${id}`;
  };
  async function refresh(next?: string) {
    const page = await api.listPayerAliases(next); setItems(page.items); setCursor(page.nextCursor);
  }
  async function load(next?: string) {
    working(true); setError('');
    try { await refresh(next); } catch (failure) { report(failure); } finally { working(false); }
  }
  async function open(id: string) {
    working(true); setError(''); setSelected(null); setLookupId(id);
    try { setSelected(await api.getPayerAlias(id)); } catch (failure) { report(failure); } finally { working(false); }
  }
  async function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy || pendingRegistration || !payerName || !customerId) return;
    working(true); setError(''); setNotice(''); let registered = false;
    try {
      const result = await api.createPayerAlias(session, { payerName, customerId });
      registered = true; setSelected(result); setLookupId(result.id); setPayerName(''); setCustomerId('');
      await refresh(); setNotice('名義と顧客の対応を登録しました。');
    } catch (failure) {
      if (!registered && uncertain(failure)) { setPendingRegistration(true); setRegistrationChecked(false); setMatches([]); }
      report(failure);
    } finally { working(false); }
  }
  async function recoverRegistration() {
    working(true); setError('');
    try {
      const found: PayerAlias[] = []; let next: string | undefined;
      do {
        const page = await api.listPayerAliases(next); found.push(...page.items.filter((item) => item.customerId === customerId)); next = page.nextCursor ?? undefined;
      } while (next);
      setMatches(found); setRegistrationChecked(true); await refresh();
      setNotice('全ページから、この顧客の有効な対応を取得しました。名義・対応ID・詳細を確認してください。登録は再送していません。');
    } catch (failure) { report(failure); } finally { working(false); }
  }
  function rows(records: PayerAlias[]) {
    return records.map((item) => <tr key={item.id}><td className="csv-input">{item.payerName}</td><td>{customerLabel(item.customerId)}</td><td>{item.id}</td><td><button type="button" className="text-button" disabled={busy} aria-label={`対応 ${item.id} の詳細`} onClick={() => open(item.id)}>詳細・無効化</button></td></tr>);
  }
  return <section className="panel" aria-labelledby="payer-alias-title">
    <h2 id="payer-alias-title">振込名義と顧客の対応</h2>
    <p>担当者が確認した名義を顧客へ対応付けます。前後の空白だけをサーバーで除き、大小文字・全半角・かな・Unicode表現は同一視しません。</p>
    <p>同じ名義を複数顧客へ対応させると、候補を一つに決められず確認が必要です。候補表示・消込機能はまだ利用できません。</p>
    <form onSubmit={register}>
      <fieldset disabled={busy || pendingRegistration}><legend>新しい対応を登録</legend>
        <label htmlFor="alias-payer-name">対応する振込名義<input id="alias-payer-name" required value={payerName} onChange={(event) => setPayerName(event.target.value)} /></label>
        <div><label htmlFor="alias-customer">対応先の顧客</label><select id="alias-customer" required value={customerId} onChange={(event) => setCustomerId(event.target.value)}><option value="">顧客を選択してください</option>{customers.map((customer) => <option key={customer.id} value={customer.id}>{customer.code} — {customer.name}</option>)}</select></div>
      </fieldset>
      <button className="primary-button" disabled={busy || pendingRegistration || !payerName || !customerId}>名義対応を登録</button>
    </form>
    {error && <p className="error" role="alert">{error}</p>}{notice && <p className="success" role="status">{notice}</p>}
    {pendingRegistration && <section aria-label="登録結果の確認"><p>登録結果が不明です。入力した名義と顧客を保持しています。登録を再送せず、一覧と原対応の詳細を取得して確認してください。</p><button type="button" className="secondary-button" disabled={busy} onClick={recoverRegistration}>登録結果を全ページから確認</button>
      {registrationChecked && <><p>この顧客の有効な対応は{matches.length}件です。前後空白を除いた名義と比較してください。無効化済みの対応は対応IDから詳細を確認できます。</p><div className="table-scroll"><table aria-label="登録結果の確認一覧"><thead><tr><th>振込名義</th><th>顧客</th><th>対応ID</th><th>操作</th></tr></thead><tbody>{rows(matches)}</tbody></table></div><button type="button" className="secondary-button" disabled={busy} onClick={() => { setPendingRegistration(false); setRegistrationChecked(false); setMatches([]); setPayerName(''); setCustomerId(''); }}>結果を確認して新しい入力に切り替える</button></>}
    </section>}
    <form onSubmit={(event) => { event.preventDefault(); void open(lookupId.trim()); }}><label htmlFor="alias-lookup">対応IDで原対応・無効化履歴を開く<input id="alias-lookup" required value={lookupId} disabled={busy} onChange={(event) => setLookupId(event.target.value)} /></label><button className="secondary-button" disabled={busy || !lookupId.trim()}>対応の詳細を取得</button></form>
    {selected && <AliasDetail key={selected.id} alias={selected} customerLabel={customerLabel(selected.customerId)} session={session} busy={busy} working={working} report={report} onChanged={async (value) => { setSelected(value); await refresh(); }} />}
    <h3>有効な名義対応</h3><p>無効化した対応は一覧から除外します。原対応IDから登録者・無効化者・日時・理由を確認できます。</p>
    {busy ? <p role="status">名義対応を処理しています…</p> : items.length === 0 ? <p>表示する有効な対応はありません。</p> : <div className="table-scroll"><table aria-label="有効な名義対応一覧"><thead><tr><th>振込名義</th><th>顧客</th><th>対応ID</th><th>操作</th></tr></thead><tbody>{rows(items)}</tbody></table></div>}
    <div className="button-row"><button type="button" className="secondary-button" disabled={busy} onClick={() => load()}>対応一覧の最初のページ</button><button type="button" className="secondary-button" disabled={busy || !cursor} onClick={() => load(cursor ?? undefined)}>対応一覧の次の50件</button></div>
  </section>;
}

type DetailProps = { alias: PayerAlias; customerLabel: string; session: Session; busy: boolean; working: (busy: boolean) => void; report: (failure: unknown) => void; onChanged: (alias: PayerAlias) => Promise<void> };
function AliasDetail({ alias, customerLabel, session, busy, working, report, onChanged }: DetailProps) {
  const [reason, setReason] = useState('');
  const [confirmedVersion, setConfirmedVersion] = useState<number | null>(null);
  const [needsReload, setNeedsReload] = useState(false);
  const [notice, setNotice] = useState('');
  async function deactivate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy || !alias.active || needsReload || confirmedVersion !== alias.version || !reason) return;
    working(true); setNotice('');
    try {
      const result = await api.deactivatePayerAlias(session, alias.id, { version: alias.version, reason }); setNeedsReload(true); setConfirmedVersion(null);
      await onChanged(result); setNeedsReload(false); setReason(''); setNotice('対応を無効化しました。原対応と理由は保持されています。');
    } catch (failure) {
      if (uncertain(failure) || (failure instanceof ApiError && (failure.status === 409 || failure.status === 404))) { setNeedsReload(true); setConfirmedVersion(null); }
      report(failure);
    } finally { working(false); }
  }
  async function reload() {
    working(true); setNotice('');
    try { const current = await api.getPayerAlias(alias.id); await onChanged(current); setNeedsReload(false); setConfirmedVersion(null); setNotice(current.active ? '最新の原対応を取得しました。入力中の理由は保持しています。対象と理由を改めて確認してください。' : '無効化済みです。原対応の日時・担当者・理由を確認してください。再送していません。'); }
    catch (failure) { report(failure); } finally { working(false); }
  }
  return <section aria-labelledby="alias-detail-title"><h3 id="alias-detail-title">原対応の詳細・無効化履歴</h3>
    <dl className="invoice-facts" aria-label="原対応の記録"><dt>原対応ID</dt><dd>{alias.id}</dd><dt>振込名義</dt><dd className="csv-input">{alias.payerName}</dd><dt>顧客</dt><dd>{customerLabel}</dd><dt>状態・版</dt><dd>{alias.active ? '有効' : '無効化済み'} / {alias.version}</dd><dt>登録者ID</dt><dd>{alias.createdByUserId}</dd><dt>登録日時</dt><dd>{time(alias.createdAt)}（日本時間）</dd>{!alias.active && <><dt>無効化者ID</dt><dd>{alias.deactivatedByUserId}</dd><dt>無効化日時</dt><dd>{alias.deactivatedAt ? `${time(alias.deactivatedAt)}（日本時間）` : '—'}</dd><dt>無効化理由</dt><dd>{alias.deactivationReason}</dd></>}</dl>
    <p>無効化すると有効な対応一覧から除外します。原対応の記録は保持し、同じ名義と顧客を再登録した場合は別の対応IDになります。</p>
    {needsReload && <p>最新の詳細を取得するまで再送できません。入力中の理由は保持しています。</p>}{notice && <p className="success" role="status">{notice}</p>}
    <form onSubmit={deactivate}><label htmlFor="alias-deactivation-reason">無効化の理由<input id="alias-deactivation-reason" required value={reason} disabled={busy || !alias.active} onChange={(event) => { setReason(event.target.value); setConfirmedVersion(null); }} /></label><label className="confirmation-check"><input type="checkbox" checked={confirmedVersion === alias.version} disabled={busy || !alias.active || needsReload} onChange={(event) => setConfirmedVersion(event.target.checked ? alias.version : null)} />この原対応と理由を確認し、無効化する</label><div className="button-row"><button className="primary-button" disabled={busy || !alias.active || needsReload || confirmedVersion !== alias.version || !reason}>名義対応の無効化を確定</button><button type="button" className="secondary-button" disabled={busy} onClick={reload}>無効化の最新詳細・処理結果を取得</button></div></form>
  </section>;
}
