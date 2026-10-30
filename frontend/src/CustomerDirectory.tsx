import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, ApiError, type Customer, type Session } from './api';
import { isBlankText } from './text-input';

type Props = {
  customers: Customer[];
  session: Session;
  busy: boolean;
  onBusyChange: (busy: boolean) => void;
  onFailure: (failure: unknown) => void;
  onReload: () => Promise<Customer[]>;
};

export default function CustomerDirectory(props: Props) {
  const [editing, setEditing] = useState<Customer | null>(null);
  const [notice, setNotice] = useState('');
  const editButton = useRef<HTMLButtonElement | null>(null);
  useEffect(() => { if (!editing) editButton.current?.focus(); }, [editing]);

  return <>
    {editing && <CustomerEditor key={editing.id} {...props} customer={editing} onCancel={() => setEditing(null)} onSaved={async (updated) => {
      await props.onReload(); setEditing(null); setNotice(`${updated.name}を更新しました。`);
    }} />}
    <section className="panel list-panel" aria-labelledby="list-title">
      <div className="list-heading"><h2 id="list-title">登録済みの顧客</h2><span>{props.customers.length} 件</span></div>
      {notice && <p className="success" role="status">{notice}</p>}
      {props.customers.length === 0 ? <div className="empty-state"><span aria-hidden="true">▦</span><h3>顧客はまだ登録されていません</h3><p>上のフォームから、最初の取引先を登録してください。</p></div> : <div className="table-scroll"><table><thead><tr><th scope="col">顧客コード</th><th scope="col">顧客名</th><th scope="col">操作</th></tr></thead><tbody>{props.customers.map((customer) => <tr key={customer.id}><td className="customer-code">{customer.code}</td><td>{customer.name}</td><td><button type="button" className="text-button" aria-label={`顧客 ${customer.code} を編集`} disabled={props.busy} onClick={(event) => { editButton.current = event.currentTarget; setEditing(customer); setNotice(''); }}>編集</button></td></tr>)}</tbody></table></div>}
    </section>
  </>;
}

function CustomerEditor({ customer, session, busy: workspaceBusy, onBusyChange, onFailure, onReload, onCancel, onSaved }: Props & { customer: Customer; onCancel: () => void; onSaved: (customer: Customer) => Promise<void> }) {
  const [code, setCode] = useState(customer.code);
  const [name, setName] = useState(customer.name);
  const [version, setVersion] = useState(customer.version);
  const [latest, setLatest] = useState<Customer | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [needsReload, setNeedsReload] = useState(false);
  const [saved, setSaved] = useState<Customer | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const codeInput = useRef<HTMLInputElement>(null);
  const comparison = useRef<HTMLHeadingElement>(null);
  const locked = busy || workspaceBusy;
  useEffect(() => { codeInput.current?.focus(); }, []);
  useEffect(() => { if (latest) comparison.current?.focus(); }, [latest]);

  function working(value: boolean) { setBusy(value); onBusyChange(value); }
  function report(failure: unknown) {
    if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) onFailure(failure);
    else setError(failure instanceof Error ? failure.message : '更新できませんでした。もう一度お試しください。');
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (locked || needsReload || saved || (latest && !acknowledged)) return;
    if (isBlankText(code) || isBlankText(name)) { setError('顧客コードと顧客名を入力してください。空白だけでは更新できません。'); return; }
    working(true); setError('');
    let updated: Customer;
    try { updated = await api.updateCustomer(session, customer.id, { code, name, version }); }
    catch (failure) {
      if (failure instanceof ApiError && (failure.code === 'VERSION_CONFLICT' || failure.status === 404 || failure.status === 0 || failure.status >= 500)) setNeedsReload(true);
      report(failure); working(false); return;
    }
    setSaved(updated);
    try { await onSaved(updated); }
    catch (failure) { report(failure); }
    finally { working(false); }
  }

  async function reload() {
    working(true); setError('');
    try {
      if (saved) { await onSaved(saved); return; }
      const current = (await onReload()).find((record) => record.id === customer.id);
      if (!current) throw new ApiError(404, '顧客が見つかりません。編集を閉じて一覧を確認してください。', 'NOT_FOUND');
      setLatest(current); setVersion(current.version); setNeedsReload(false); setAcknowledged(false);
    } catch (failure) { report(failure); }
    finally { working(false); }
  }

  return <section className="panel" aria-labelledby="edit-customer-title" onKeyDown={(event) => { if (event.key === 'Escape' && !locked) onCancel(); }}>
    <div className="panel-heading"><span className="step-number">編集</span><div><h2 id="edit-customer-title">顧客を編集</h2><p>既存顧客はCSVで上書きせず、この画面で変更します。</p></div></div>
    <form onSubmit={save}>
      <div className="customer-edit-fields">
        <label htmlFor="edit-customer-code">編集中の顧客コード<input id="edit-customer-code" ref={codeInput} required value={code} disabled={locked || !!saved} onChange={(event) => setCode(event.target.value)} /></label>
        <label htmlFor="edit-customer-name">編集中の顧客名<input id="edit-customer-name" required value={name} disabled={locked || !!saved} onChange={(event) => setName(event.target.value)} /></label>
      </div>
      {latest && <div className="customer-comparison"><h3 tabIndex={-1} ref={comparison}>最新の登録内容</h3><dl><dt>顧客コード</dt><dd>{latest.code}</dd><dt>顧客名</dt><dd>{latest.name}</dd></dl><p className="import-guide">入力中の内容は保持しています。最新の登録内容と比較してから、変更を確定してください。</p><label className="confirmation-check"><input type="checkbox" checked={acknowledged} disabled={locked || !!saved} onChange={(event) => setAcknowledged(event.target.checked)} />最新の登録内容を確認し、入力中の内容で更新する</label></div>}
      {error && <p className="error" role="alert">{error}</p>}
      {saved && <p className="success">更新は完了しています。一覧の再取得を行ってください。再送は行いません。</p>}
      <div className="button-row"><button className="primary-button" disabled={locked || needsReload || !!saved || (!!latest && !acknowledged)}>変更を保存</button>{(needsReload || saved) && <button type="button" className="secondary-button" disabled={locked} onClick={reload}>{saved ? '一覧を再取得して閉じる' : '最新内容を取得'}</button>}<button type="button" className="secondary-button" disabled={locked} onClick={onCancel}>編集を閉じる</button></div>
    </form>
  </section>;
}
