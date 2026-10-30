import { useState, type FormEvent } from 'react';
import { api, ApiError, type CollectionCase, type CollectionDraft, type CollectionHistory, type Session } from './api';
import { formatJstTimestamp } from './timestamp-presentation';
import { isBlankText } from './text-input';

type Pending = { kind: CollectionHistory['kind']; version: number; note: string; nextActionDate: string | null; reason: string };
// Compare saved history using Java String.strip semantics; raw input is always sent.
function storedText(value: string) {
  const chars = [...value]; let start = 0; let end = chars.length;
  while (start < end && isBlankText(chars[start])) start++;
  while (end > start && isBlankText(chars[end - 1])) end--;
  return chars.slice(start, end).join('');
}
const failureMessage = (failure: unknown) => failure instanceof Error ? failure.message : '操作できませんでした。';

export default function CollectionActionsPanel({ item, session, busy, onBusyChange, onSessionExpired, onRefresh }: { item: CollectionCase; session: Session; busy: boolean; onBusyChange: (busy: boolean) => void; onSessionExpired: (failure: ApiError) => void; onRefresh: () => Promise<void> }) {
  const [note, setNote] = useState('');
  // Pristine dates follow refreshed server state; an edited empty string explicitly clears the schedule.
  const [nextDateDraft, setNextDateDraft] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState<Pending | null>(null);
  const [checked, setChecked] = useState(false);
  const [recovered, setRecovered] = useState(false);
  const [events, setEvents] = useState<CollectionHistory[]>([]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [draft, setDraft] = useState<CollectionDraft | null>(null);
  const nextDate = nextDateDraft ?? item.nextActionDate ?? '';
  const noteCount = [...note].length;
  const reasonCount = [...storedText(reason)].length;

  function report(failure: unknown) {
    if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) onSessionExpired(failure);
    else setError(failureMessage(failure));
  }
  async function save(kind: Pending['kind']) {
    if (busy || pending) return;
    const attempt: Pending = { kind, version: item.version, note, nextActionDate: nextDate || null, reason };
    if (kind === 'NOTE' ? isBlankText(note) : isBlankText(reason)) { setError(kind === 'NOTE' ? '対応メモを入力してください。' : '保留または解除の理由を入力してください。'); return; }
    if (kind === 'NOTE' ? noteCount > 5000 : reasonCount > 1000) { setError(kind === 'NOTE' ? '対応メモは5,000文字以内で入力してください。' : '保留・解除の理由は1,000文字以内で入力してください。'); return; }
    onBusyChange(true); setError(''); setNotice(''); setDraft(null);
    try {
      if (kind === 'NOTE') await api.recordCollectionAction(session, item.invoiceId, { version: attempt.version, note: attempt.note, nextActionDate: attempt.nextActionDate });
      else if (kind === 'HOLD') await api.holdCollection(session, item.invoiceId, { version: attempt.version, reason: attempt.reason });
      else await api.releaseCollection(session, item.invoiceId, { version: attempt.version, reason: attempt.reason });
      if (kind === 'NOTE') setNote(''); else setReason('');
      setNotice('対応を記録しました。');
      await onRefresh();
      if (kind === 'NOTE') setNextDateDraft(null);
    } catch (failure) {
      report(failure);
      if (!(failure instanceof ApiError) || failure.status === 0 || failure.status >= 500 || failure.status === 409) { setPending(attempt); setRecovered(false); setChecked(false); setEvents([]); }
    } finally { onBusyChange(false); }
  }
  async function recover() {
    onBusyChange(true); setError(''); setDraft(null); setChecked(false);
    try {
      const after = pending ? await api.listCollectionHistory(item.invoiceId, pending.version) : null;
      await onRefresh(); setEvents(after?.items ?? []); setRecovered(true);
      const match = pending && after?.items.some((event) => event.version === pending.version + 1 && event.actorUserId === session.userId && event.kind === pending.kind && (pending.kind === 'NOTE' ? event.note === storedText(pending.note) && event.nextActionDate === pending.nextActionDate : event.reason === storedText(pending.reason)));
      if (match) {
        setPending(null); if (pending.kind === 'NOTE') { setNote(''); setNextDateDraft(null); } else setReason('');
        setNotice('送信内容と一致する記録を履歴で確認しました。再送はしません。');
      } else setNotice('最新状況と送信後の履歴を取得しました。入力中の内容と比較してください。');
    } catch (failure) { report(failure); }
    finally { onBusyChange(false); }
  }
  async function preview(copy = false) {
    onBusyChange(true); setError(''); setNotice('');
    const previous = draft; setDraft(null);
    try {
      // Re-read at copy time. A changed balance/body must be read before a later explicit copy.
      const current = await api.previewCollectionDraft(item.invoiceId, item.asOf); setDraft(current);
      if (copy && previous && JSON.stringify(current) === JSON.stringify(previous)) {
        try { await navigator.clipboard.writeText(`${current.subject}\n\n${current.body}`); setNotice('表示した件名と文面をコピーしました。外部には送信していません。'); }
        catch { setError('コピーできませんでした。ブラウザーの権限を確認するか、表示した文面を選択して手動でコピーしてください。'); }
      } else if (copy) setNotice('残高または文面が変わりました。更新した内容を読んでから、もう一度コピーしてください。');
    } catch (failure) { report(failure); }
    finally { onBusyChange(false); }
  }

  return <section aria-label="対応を記録・文面を作成">
    <h3>対応を記録</h3><p>対応メモは共有され、完済・取消後も追記できます。登録済みの原本は書き換えません。</p>
    {error && <p className="error" role="alert">{error}</p>}{notice && <p className="success" role="status">{notice}</p>}
    {pending && <div className="import-guide"><h4>送信結果・最新の版を確認してください</h4><p>第{pending.version}版に対する操作は再送を止めています。GETで現在の状態と履歴を確認します。入力中のメモ・理由は保持しています。</p><p style={{ whiteSpace: 'pre-wrap' }}>送信した内容：{pending.kind === 'NOTE' ? `${pending.note}／次回：${pending.nextActionDate ?? '未設定'}` : pending.reason}</p><p>現在：第{item.version}版／{item.held ? '保留中' : '保留なし'}／次回：{item.nextActionDate ?? '未設定'}／最新メモ：{item.latestNote ?? 'なし'}／保留理由：{item.holdReason ?? 'なし'}</p>
      <button type="button" disabled={busy} onClick={() => void recover()}>GETで結果・最新状況を確認</button>
      {recovered && <><ul>{events.map((event) => <li key={event.version}>第{event.version}版：{event.kind}／{event.actorLabel}／{formatJstTimestamp(event.occurredAt)}／{event.note ?? event.reason}／次回：{event.nextActionDate ?? '未設定'}</li>)}</ul>{events.length === 0 && <p>送信時の版以降に記録はありません。</p>}<label><input type="checkbox" checked={checked} disabled={busy} onChange={(event) => setChecked(event.target.checked)} />最新状況と原本履歴を読み、入力中の内容を比較しました</label><button type="button" disabled={busy || !checked} onClick={() => { setPending(null); setChecked(false); setNotice('最新の版で入力内容を再確認してください。まだ送信していません。'); }}>最新の版で再確認に戻る</button></>}
    </div>}
    <form onSubmit={(event: FormEvent) => { event.preventDefault(); void save('NOTE'); }}>
      <label htmlFor="collection-note">対応メモ（共有）<textarea id="collection-note" required aria-describedby="collection-note-count" aria-invalid={noteCount > 5000} value={note} disabled={busy || !!pending} onChange={(event) => setNote(event.target.value)} /></label>
      <p id="collection-note-count" className={noteCount > 5000 ? 'error' : 'helper'}>{noteCount.toLocaleString('ja-JP')} / 5,000文字{noteCount > 5000 && '：上限を超えています。内容を短くしてください。'}</p>
      <label htmlFor="collection-next-date">次回対応日（空欄で予定を解除）<input id="collection-next-date" type="date" value={nextDate} disabled={busy || !!pending} onChange={(event) => setNextDateDraft(event.target.value)} /></label>
      <p className="helper">未編集の予定日は最新状況の再取得に追随します。空欄は「未設定」として明示的に記録します。自動で保留を解除したり通知を送りません。</p>
      {nextDateDraft !== null && nextDate !== (item.nextActionDate ?? '') && <p className="import-guide">入力中の予定：{nextDate || '未設定'}／現在の登録予定：{item.nextActionDate ?? '未設定'}。入力中の予定を保持しています。<button type="button" className="text-button" disabled={busy || !!pending} onClick={() => setNextDateDraft(null)}>現在の登録予定を使う</button></p>}
      <button className="primary-button" disabled={busy || !!pending || noteCount > 5000}>対応メモを記録</button>
    </form>
    <label htmlFor="collection-reason">保留・解除の理由<textarea id="collection-reason" aria-describedby="collection-reason-count" aria-invalid={reasonCount > 1000} value={reason} disabled={busy || !!pending} onChange={(event) => setReason(event.target.value)} /></label>
    <p id="collection-reason-count" className={reasonCount > 1000 ? 'error' : 'helper'}>{reasonCount.toLocaleString('ja-JP')} / 1,000文字{reasonCount > 1000 && '：上限を超えています。内容を短くしてください。'}</p>
    <button type="button" className="secondary-button" disabled={busy || !!pending || reasonCount > 1000} onClick={() => void save(item.held ? 'RELEASE' : 'HOLD')}>{item.held ? '理由を記録して保留を解除' : '理由を記録して保留'}</button>
    <h3>確認用の文面</h3><p>宛名・請求書番号・支払期日・現在の未回収額を読み、必要ならコピーしてください。外部送信は行いません。</p>
    <button type="button" disabled={busy || !!pending} onClick={() => void preview()}>現在の残高で文面を作成</button>
    {draft && <div>{draft.held && <p className="import-guide">この請求は保留中です。理由：{draft.holdReason}。連絡するかは担当者が判断してください。</p>}<h4>宛名：{draft.customerName} 御中</h4><p>基準日：{draft.asOf}（日本の業務日）／現在の未回収額：{new Intl.NumberFormat('ja-JP').format(draft.outstandingAmount)} 円</p><p>件名：{draft.subject}</p><pre style={{ whiteSpace: 'pre-wrap' }}>{draft.body}</pre><button type="button" disabled={busy || !!pending} onClick={() => void preview(true)}>表示した文面をコピー</button></div>}
    <button type="button" className="text-button" disabled={busy} onClick={() => void recover()}>最新状況をGETで再取得</button>
  </section>;
}
