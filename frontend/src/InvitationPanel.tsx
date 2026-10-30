import { useRef, useState, type FormEvent } from 'react';
import { api, ApiError, type InvitationIssued, type InvitationRole, type Session } from './api';
import { invitationExpiry, invitationRoleNames, shareableInvitationUrl } from './invitation-presentation';

export default function InvitationPanel({ session, onSessionExpired, onBusyChange }: { session: Session; onSessionExpired: (failure: ApiError) => void; onBusyChange?: (busy: boolean) => void }) {
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<InvitationRole>('ACCOUNTANT');
  const [issued, setIssued] = useState<(InvitationIssued & { shareUrl: string; recipient: string; role: InvitationRole }) | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const linkInput = useRef<HTMLInputElement>(null);

  async function issue(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); onBusyChange?.(true); setError(''); setNotice(''); setIssued(null);
    try {
      const result = await api.issueInvitation(session, { email: email.trim(), role });
      setIssued({ ...result, shareUrl: shareableInvitationUrl(result.acceptanceUrl), recipient: email.trim(), role });
      setEmail('');
    } catch (failure) {
      if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) onSessionExpired(failure);
      else setError(failure instanceof Error ? failure.message : '招待を作成できませんでした。もう一度お試しください。');
    } finally { setBusy(false); onBusyChange?.(false); }
  }

  async function copyLink() {
    if (!issued) return;
    try { await navigator.clipboard.writeText(issued.shareUrl); setNotice('招待リンクをコピーしました。招待する相手に共有してください。'); }
    catch {
      linkInput.current?.focus(); linkInput.current?.select();
      setNotice('自動コピーできませんでした。招待リンク欄を選択して、手動でコピーしてください。');
    }
  }

  if (session.role !== 'ADMIN') return <p className="error" role="alert">この操作を行う権限がありません。管理者にご確認ください。</p>;
  return <section className="panel invitation-panel" aria-labelledby="invite-title">
    <div className="panel-heading"><span className="step-number">＋</span><div><h2 id="invite-title">メンバーを招待</h2><p>招待リンクは24時間有効です。メールは送信されません。作成したリンクを相手に共有してください。</p></div></div>
    <form className="invitation-form" onSubmit={issue}>
      <label htmlFor="invite-email">招待先メールアドレス<input id="invite-email" type="email" autoComplete="off" required value={email} onChange={(event) => setEmail(event.target.value)} disabled={busy} /></label>
      <div><label htmlFor="invite-role">付与する権限</label><select id="invite-role" value={role} onChange={(event) => setRole(event.target.value as InvitationRole)} disabled={busy}><option value="ACCOUNTANT">経理担当者</option><option value="ADMIN">管理者</option></select></div>
      <p className="helper">管理者はメンバーを招待できます。経理担当者は顧客登録や経理業務を行います。</p>
      <button className="primary-button" disabled={busy}>{busy ? '作成中…' : '招待リンクを作成'}</button>
    </form>
    {error && <p className="error" role="alert">{error}</p>}
    {issued && <div className="issued-invitation" aria-labelledby="issued-title">
      <h3 id="issued-title">招待リンクを作成しました</h3>
      <p>招待先：{issued.recipient}（{invitationRoleNames[issued.role]}）</p>
      <p>有効期限：{invitationExpiry(issued.expiresAt)}</p>
      <p>この画面を離れるとリンクは再表示できません。招待する相手にだけ共有してください。</p>
      <label htmlFor="invitation-link">招待リンク<input id="invitation-link" ref={linkInput} data-sensitive readOnly value={issued.shareUrl} onFocus={(event) => event.currentTarget.select()} /></label>
      <div className="button-row"><button type="button" className="primary-button" onClick={copyLink}>リンクをコピー</button><button type="button" className="secondary-button" onClick={() => { setIssued(null); setNotice(''); }}>リンク表示を閉じる</button></div>
    </div>}
    {notice && <p className="success" role="status">{notice}</p>}
  </section>;
}
