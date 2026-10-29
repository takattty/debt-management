import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError, type Customer, type Session } from './api';
import InvitationPanel from './InvitationPanel';
import AcceptInvitation from './AcceptInvitation';
import type { InvitationEntry } from './invitation-entry';

const errorMessage = (error: unknown) => error instanceof Error ? error.message : '処理に失敗しました。もう一度お試しください。';
const roleNames: Record<Session['role'], string> = { ADMIN: '管理者', ACCOUNTANT: '経理担当者' };

export default function App({ invitationEntry }: { invitationEntry?: InvitationEntry } = {}) {
  return invitationEntry ? <AcceptInvitation token={invitationEntry.token} /> : <Workspace />;
}

function Workspace() {
  const [session, setSession] = useState<Session | null>(null);
  const [restoring, setRestoring] = useState(true);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [page, setPage] = useState<'customers' | 'invitations'>('customers');

  useEffect(() => {
    let active = true;
    async function restoreSession() {
      try {
        const current = await api.getSession();
        const records = await api.listCustomers();
        if (active) { setSession(current); setCustomers(records); }
      } catch (failure) {
        if (active && !(failure instanceof ApiError && failure.status === 401)) setError(errorMessage(failure));
      } finally {
        if (active) setRestoring(false);
      }
    }
    void restoreSession();
    return () => { active = false; };
  }, []);

  function clearWorkspace() {
    setSession(null); setCustomers([]); setCode(''); setName(''); setNotice(''); setPassword(''); setPage('customers');
  }

  function reportWorkspaceFailure(failure: unknown) {
    if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) {
      clearWorkspace(); setError(failure.message); return;
    }
    setError(errorMessage(failure));
  }

  async function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true); setError('');
    try {
      const csrf = await api.getCsrf();
      await api.login({ email: email.trim(), password }, csrf);
      setPassword('');
      const current = await api.getSession();
      const records = await api.listCustomers();
      setCustomers(records); setSession(current);
    } catch (failure) { setError(errorMessage(failure)); }
    finally { setBusy(false); }
  }

  async function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!session) return;
    setError(''); setNotice('');
    if (!code.trim() || !name.trim()) {
      setError('顧客コードと顧客名を入力してください。空白だけでは登録できません。');
      return;
    }
    setBusy(true);
    try {
      const created = await api.createCustomer(session, { code: code.trim(), name: name.trim() });
      setCustomers((records) => [...records, created]);
      setCode(''); setName(''); setNotice(`${created.name}を登録しました。`);
    } catch (failure) { reportWorkspaceFailure(failure); }
    finally { setBusy(false); }
  }

  async function logout() {
    if (!session) return;
    setBusy(true); setError('');
    try {
      await api.logout(session);
      clearWorkspace(); setEmail(''); setError('');
    } catch (failure) { reportWorkspaceFailure(failure); }
    finally { setBusy(false); }
  }

  return <div className="app-shell">
    <aside className="sidebar">
      <a className="brand" href="/" aria-label="債権管理 ホーム"><span className="brand-mark">債</span><span>債権管理<small>RECEIVABLES WORKSPACE</small></span></a>
      <p className="nav-caption">WORKSPACE</p>
      <button className={page === 'customers' ? 'nav-current nav-button' : 'nav-button'} disabled={busy} onClick={() => setPage('customers')}><span aria-hidden="true">▦</span> 顧客管理</button>
      {session?.role === 'ADMIN' && <button className={page === 'invitations' ? 'nav-current nav-button' : 'nav-button'} disabled={busy} onClick={() => setPage('invitations')}>メンバー招待</button>}
      <div className="sidebar-foot"><span className="status-dot" /> ローカル検証環境<p>架空データでご利用ください</p></div>
    </aside>
    <main>
      <header className="topbar"><span>債権管理 / {page === 'invitations' ? 'メンバー招待' : '顧客管理'}</span>{session && <div className="account"><span className="account-email" title={session.email}>{session.email}</span><span className="account-role">{roleNames[session.role]}</span><button className="text-button" disabled={busy} onClick={logout}>ログアウト</button></div>}</header>
      <div className="content">
        <div className="page-heading"><div><p className="eyebrow">{page === 'invitations' ? 'MEMBERS' : 'CUSTOMERS'}</p><h1>{page === 'invitations' ? 'メンバー招待' : '顧客管理'}</h1><p className="subtitle">{page === 'invitations' ? '同じ契約企業で利用するメンバーを招待します。' : '請求・入金管理の基礎となる取引先を登録します。'}</p></div>{session && page === 'customers' && <span className="count-badge">登録顧客 <strong>{customers.length}</strong> 件</span>}</div>
        {restoring ? <section className="panel login-panel"><p role="status">ログイン状態を確認しています…</p></section> : !session ? <section className="panel login-panel" aria-labelledby="login-title">
          <div className="panel-heading"><span className="step-number">01</span><div><h2 id="login-title">ワークスペースにログイン</h2><p>所属する契約企業の顧客情報を表示します。</p></div></div>
          <form onSubmit={login}>
            <label htmlFor="email">メールアドレス<input id="email" type="email" autoComplete="username" required value={email} onChange={(event) => setEmail(event.target.value)} disabled={busy} /></label>
            <label htmlFor="password">パスワード<input id="password" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} disabled={busy} /></label>
            {error && <p className="error" role="alert">{error}</p>}
            <button className="primary-button" disabled={busy}>{busy ? '確認中…' : 'ログイン'}</button>
          </form>
          <p className="helper">登録されたメールアドレスでログインしてください。</p>
        </section> : page === 'invitations' && session.role === 'ADMIN' ? <InvitationPanel session={session} onSessionExpired={reportWorkspaceFailure} onBusyChange={setBusy} /> : <>
          <section className="panel" aria-labelledby="register-title">
            <div className="panel-heading"><span className="step-number">＋</span><div><h2 id="register-title">顧客を登録</h2><p>顧客コードは契約企業内で重複しない値を指定してください。</p></div></div>
            <form className="customer-form" onSubmit={register}>
              <label htmlFor="code">顧客コード <span className="required">必須</span><input id="code" required placeholder="例：CUST-001" value={code} onChange={(event) => setCode(event.target.value)} disabled={busy} /></label>
              <label htmlFor="customer-name">顧客名 <span className="required">必須</span><input id="customer-name" required placeholder="例：株式会社サンプル" value={name} onChange={(event) => setName(event.target.value)} disabled={busy} /></label>
              <button className="primary-button" disabled={busy}>{busy ? '登録中…' : '顧客を登録'}</button>
            </form>
            {error && <p className="error" role="alert">{error}</p>}
            {notice && <p className="success" role="status">{notice}</p>}
          </section>
          <section className="panel list-panel" aria-labelledby="list-title">
            <div className="list-heading"><h2 id="list-title">登録済みの顧客</h2><span>{customers.length} 件</span></div>
            {customers.length === 0 ? <div className="empty-state"><span aria-hidden="true">▦</span><h3>顧客はまだ登録されていません</h3><p>上のフォームから、最初の取引先を登録してください。</p></div> : <div className="table-scroll"><table><thead><tr><th scope="col">顧客コード</th><th scope="col">顧客名</th></tr></thead><tbody>{customers.map((customer) => <tr key={customer.id}><td className="customer-code">{customer.code}</td><td>{customer.name}</td></tr>)}</tbody></table></div>}
          </section>
        </>}
        <footer className="content-foot">顧客情報は所属する契約企業の中で共有されます。</footer>
      </div>
    </main>
  </div>;
}
