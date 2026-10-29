import { useState, type FormEvent } from 'react';
import { api, type Credentials, type Customer } from './api';

const errorMessage = (error: unknown) => error instanceof Error ? error.message : '処理に失敗しました。もう一度お試しください。';

export default function App() {
  const [credentials, setCredentials] = useState<Credentials | null>(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  async function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true); setError('');
    const candidate = { username, password };
    try {
      const session = await api.getSession(candidate);
      const authenticated = { ...candidate, csrfToken: session.csrfToken, csrfHeaderName: session.csrfHeaderName };
      const records = await api.listCustomers(authenticated);
      setCustomers(records); setCredentials(authenticated); setPassword('');
    } catch (failure) { setError(errorMessage(failure)); }
    finally { setBusy(false); }
  }

  async function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!credentials) return;
    setError(''); setNotice('');
    if (!code.trim() || !name.trim()) {
      setError('顧客コードと顧客名を入力してください。空白だけでは登録できません。');
      return;
    }
    setBusy(true);
    try {
      const created = await api.createCustomer(credentials, { code: code.trim(), name: name.trim() });
      setCustomers((records) => [...records, created]);
      setCode(''); setName(''); setNotice(`${created.name}を登録しました。`);
    } catch (failure) { setError(errorMessage(failure)); }
    finally { setBusy(false); }
  }

  async function logout() {
    if (!credentials) return;
    setBusy(true); setError('');
    try {
      await api.logout(credentials);
      setCredentials(null); setCustomers([]); setUsername(''); setPassword('');
      setCode(''); setName(''); setError(''); setNotice('');
    } catch (failure) { setError(errorMessage(failure)); }
    finally { setBusy(false); }
  }

  return <div className="app-shell">
    <aside className="sidebar">
      <a className="brand" href="/" aria-label="債権管理 ホーム"><span className="brand-mark">債</span><span>債権管理<small>RECEIVABLES WORKSPACE</small></span></a>
      <p className="nav-caption">WORKSPACE</p>
      <div className="nav-current"><span aria-hidden="true">▦</span> 顧客管理</div>
      <div className="sidebar-foot"><span className="status-dot" /> ローカル検証環境<p>架空データでご利用ください</p></div>
    </aside>
    <main>
      <header className="topbar"><span>債権管理 / 顧客管理</span>{credentials && <div className="account"><span>{credentials.username}</span><button className="text-button" disabled={busy} onClick={logout}>ログアウト</button></div>}</header>
      <div className="content">
        <div className="page-heading"><div><p className="eyebrow">CUSTOMERS</p><h1>顧客管理</h1><p className="subtitle">請求・入金管理の基礎となる取引先を登録します。</p></div>{credentials && <span className="count-badge">登録顧客 <strong>{customers.length}</strong> 件</span>}</div>
        {!credentials ? <section className="panel login-panel" aria-labelledby="login-title">
          <div className="panel-heading"><span className="step-number">01</span><div><h2 id="login-title">ワークスペースにログイン</h2><p>所属する契約企業の顧客情報を表示します。</p></div></div>
          <form onSubmit={login}>
            <label htmlFor="username">ユーザー名<input id="username" autoComplete="username" required value={username} onChange={(event) => setUsername(event.target.value)} disabled={busy} /></label>
            <label htmlFor="password">パスワード<input id="password" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} disabled={busy} /></label>
            {error && <p className="error" role="alert">{error}</p>}
            <button className="primary-button" disabled={busy}>{busy ? '確認中…' : 'ログイン'}</button>
          </form>
          <p className="helper">開発用アカウントはREADMEの起動手順をご確認ください。ログイン情報はこの画面を閉じると破棄されます。</p>
        </section> : <>
          <section className="panel" aria-labelledby="register-title">
            <div className="panel-heading"><span className="step-number">＋</span><div><h2 id="register-title">顧客を登録</h2><p>顧客コードは契約企業内で重複しない値を指定してください。</p></div></div>
            <form className="customer-form" onSubmit={register}>
              <label htmlFor="code">顧客コード <span className="required">必須</span><input id="code" required maxLength={50} placeholder="例：CUST-001" value={code} onChange={(event) => setCode(event.target.value)} disabled={busy} /></label>
              <label htmlFor="customer-name">顧客名 <span className="required">必須</span><input id="customer-name" required maxLength={200} placeholder="例：株式会社サンプル" value={name} onChange={(event) => setName(event.target.value)} disabled={busy} /></label>
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
