import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { api, ApiError, type InvitationInspection, type Session } from './api';
import { invalidInvitationMessage, invitationExpiry, invitationRoleNames } from './invitation-presentation';

type Bootstrap = { kind: 'signed-in'; session: Session } | { kind: 'ready'; csrf: Awaited<ReturnType<typeof api.getCsrf>>; inspection: InvitationInspection };

async function inspect(token: string): Promise<Bootstrap> {
  try { return { kind: 'signed-in', session: await api.getSession() }; }
  catch (failure) { if (!(failure instanceof ApiError && failure.status === 401)) throw failure; }
  const csrf = await api.getCsrf();
  return { kind: 'ready', csrf, inspection: await api.inspectInvitation(csrf, { token }) };
}

export default function AcceptInvitation({ token }: { token: string | null }) {
  const [phase, setPhase] = useState<'loading' | 'signed-in' | 'ready' | 'invalid' | 'failed' | 'complete'>(token ? 'loading' : 'invalid');
  const [existing, setExisting] = useState<Session | null>(null);
  const [preview, setPreview] = useState<InvitationInspection | null>(null);
  const [csrf, setCsrf] = useState<Awaited<ReturnType<typeof api.getCsrf>> | null>(null);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const pending = useRef<{ token: string; result: Promise<Bootstrap> } | null>(null);
  const apply = useCallback((result: Bootstrap) => {
    setError(''); setPassword('');
    if (result.kind === 'signed-in') { setExisting(result.session); setPreview(null); setCsrf(null); setPhase('signed-in'); }
    else { setExisting(null); setPreview(result.inspection); setCsrf(result.csrf); setPhase('ready'); }
  }, []);
  const reject = useCallback((failure: unknown) => {
    setPassword('');
    if (failure instanceof ApiError && (failure.status === 400 || failure.status === 409)) setPhase('invalid');
    else { setPhase('failed'); setError(failure instanceof Error ? failure.message : '招待を確認できませんでした。もう一度お試しください。'); }
  }, []);

  useEffect(() => {
    if (!token) return;
    let active = true;
    if (pending.current?.token !== token) pending.current = { token, result: inspect(token) };
    pending.current.result.then((result) => { if (active) apply(result); }).catch((failure: unknown) => { if (active) reject(failure); });
    return () => { active = false; };
  }, [token, apply, reject]);

  async function retry() {
    if (!token) return;
    setPhase('loading'); setError(''); setBusy(true);
    try { apply(await inspect(token)); } catch (failure) { reject(failure); }
    finally { setBusy(false); }
  }

  async function signOutAndContinue() {
    if (!existing) return;
    setBusy(true); setError('');
    try { await api.logout(existing); await retry(); }
    catch (failure) {
      if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) await retry();
      else setError(failure instanceof Error ? failure.message : 'ログアウトできませんでした。もう一度お試しください。');
    } finally { setBusy(false); }
  }

  async function accept(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!token || !csrf) return;
    setBusy(true); setError('');
    try {
      try { apply({ kind: 'signed-in', session: await api.getSession() }); return; }
      catch (failure) { if (!(failure instanceof ApiError && failure.status === 401)) throw failure; }
      await api.acceptInvitation(csrf, { token, email: email.trim(), password });
      setPassword(''); setCsrf(null); setPreview(null); setPhase('complete');
    } catch (failure) {
      if (failure instanceof ApiError && (failure.code === 'INVITATION_INVALID' || failure.status === 409)) setPhase('invalid');
      else if (failure instanceof ApiError && failure.code === 'CSRF_INVALID') { setPhase('failed'); setError('操作の有効期限が切れました。招待をもう一度確認してください。'); }
      else setError(failure instanceof Error ? failure.message : '登録できませんでした。入力内容を確認してください。');
    } finally { setBusy(false); setPassword(''); }
  }

  async function goToLogin() {
    setBusy(true); setError('');
    try {
      try { await api.logout(await api.getSession()); }
      catch (failure) { if (!(failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID'))) throw failure; }
      window.location.assign('/');
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'ログイン画面に進めませんでした。もう一度お試しください。'); }
    finally { setBusy(false); }
  }

  return <main className="accept-shell"><a className="accept-brand" href="/">債権管理</a><section className="panel accept-panel" aria-labelledby="accept-title">
    <p className="eyebrow">MEMBER INVITATION</p><h1 id="accept-title">メンバー招待</h1>
    {phase === 'loading' && <p role="status">招待を確認しています…</p>}
    {phase === 'invalid' && <><p className="error" role="alert">{invalidInvitationMessage}</p><a className="secondary-button link-button" href="/">ログイン画面へ</a></>}
    {phase === 'failed' && <><p className="error" role="alert">{error}</p><button className="primary-button" disabled={busy} onClick={retry}>招待を再確認</button></>}
    {phase === 'signed-in' && existing && <><p>{existing.email}でログインしています。</p><p>この招待を受けるには、現在のアカウントからログアウトしてください。</p><div className="button-row"><button className="primary-button" disabled={busy} onClick={signOutAndContinue}>ログアウトして招待を確認</button><a className="secondary-button link-button" href="/">現在のアカウントを続ける</a></div></>}
    {phase === 'ready' && preview && <>
      <dl className="invitation-details"><div><dt>契約企業</dt><dd>{preview.tenantDisplayName}</dd></div><div><dt>招待先</dt><dd>{preview.maskedEmail}</dd></div><div><dt>権限</dt><dd>{invitationRoleNames[preview.role]}</dd></div><div><dt>有効期限</dt><dd>{invitationExpiry(preview.expiresAt)}</dd></div></dl>
      <form className="invitation-form" onSubmit={accept}>
        <label htmlFor="accept-email">招待されたメールアドレス<input id="accept-email" type="email" autoComplete="username" required value={email} onChange={(event) => setEmail(event.target.value)} disabled={busy} /></label>
        <label htmlFor="accept-password">新しいパスワード<input id="accept-password" type="password" autoComplete="new-password" required aria-describedby="password-hint" value={password} onChange={(event) => setPassword(event.target.value)} disabled={busy} /></label>
        <p id="password-hint" className="helper">12〜128文字で設定してください。前後の空白もパスワードの一部になります。</p>
        <button className="primary-button" disabled={busy}>{busy ? '登録中…' : '招待を受けて登録'}</button>
      </form>
    </>}
    {phase === 'complete' && <><p className="success" role="status">アカウントを登録しました。</p><p>登録したメールアドレスとパスワードでログインしてください。</p><p>別のアカウントでログイン中の場合は、ログアウトしてからログイン画面に進みます。</p><button className="primary-button" disabled={busy} onClick={goToLogin}>ログイン画面へ進む</button></>}
    {error && phase !== 'failed' && phase !== 'invalid' && <p className="error" role="alert">{error}</p>}
  </section></main>;
}
