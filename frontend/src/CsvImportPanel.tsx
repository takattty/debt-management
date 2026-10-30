import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, ApiError, type ImportBatch, type ImportError, type ImportRow, type Session } from './api';

type Props = {
  kind?: ImportBatch['kind'];
  confirmationEnabled?: boolean;
  session: Session;
  onSessionExpired: (failure: ApiError) => void;
  onBusyChange: (busy: boolean) => void;
  onConfirmed?: () => Promise<void>;
  onShowRecords?: () => Promise<void>;
};

function saveCsv(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url; link.download = filename; link.click();
  const revoke = URL.revokeObjectURL.bind(URL);
  setTimeout(() => revoke(url), 1000);
}

export default function CsvImportPanel({ kind = 'CUSTOMER', confirmationEnabled = true, session, onSessionExpired, onBusyChange, onConfirmed, onShowRecords }: Props) {
  const invoice = kind === 'INVOICE';
  const recordName = invoice ? '請求' : '顧客';
  const [file, setFile] = useState<File | null>(null);
  const [batch, setBatch] = useState<ImportBatch | null>(null);
  const [rows, setRows] = useState<ImportRow[]>([]);
  const [errors, setErrors] = useState<ImportError[]>([]);
  const [nextRow, setNextRow] = useState(0);
  const [nextError, setNextError] = useState(0);
  const [viewed, setViewed] = useState(false);
  const [recovery, setRecovery] = useState(false);
  const [originalBatchId, setOriginalBatchId] = useState('');
  const [blockedBatchId, setBlockedBatchId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const errorHeading = useRef<HTMLHeadingElement>(null);

  useEffect(() => { if (batch && batch.errorCount > 0) errorHeading.current?.focus(); }, [batch]);

  function working(value: boolean) { setBusy(value); onBusyChange(value); }
  function report(failure: unknown) {
    if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) onSessionExpired(failure);
    else setError(failure instanceof Error ? failure.message : '処理に失敗しました。もう一度お試しください。');
  }

  async function showBatch(current: ImportBatch) {
    if (current.kind !== kind) throw new ApiError(400, '取込形式が一致しません。CSVをもう一度プレビューしてください。', 'INVALID_REQUEST');
    setBatch(current); setRows([]); setErrors([]); setNextRow(0); setNextError(0); setViewed(false);
    setOriginalBatchId(current.originalBatchId ?? '');
    const [rowPage, errorPage] = await Promise.all([api.listImportRows(current.id), api.listImportErrors(current.id)]);
    setRows(rowPage.items); setNextRow(rowPage.nextCursor);
    setErrors(errorPage.items); setNextError(errorPage.nextCursor); setViewed(true);
  }

  async function download(kind: 'template' | 'errors') {
    working(true); setError('');
    try {
      if (kind === 'template') saveCsv(await (invoice ? api.downloadInvoiceImportTemplate() : api.downloadCustomerImportTemplate()), invoice ? 'invoices-template.csv' : 'customers-template.csv');
      else if (batch) saveCsv(await api.downloadImportErrors(batch.id), 'import-errors.csv');
    } catch (failure) { report(failure); }
    finally { working(false); }
  }

  async function preview(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file) { setError('プレビューするCSVファイルを選択してください。'); return; }
    working(true); setError(''); setNotice(''); setRecovery(false); setOriginalBatchId('');
    setBatch(null); setRows([]); setErrors([]); setViewed(false);
    try { await showBatch(await (invoice ? api.previewInvoiceImport(session, file) : api.previewCustomerImport(session, file))); }
    catch (failure) { report(failure); }
    finally { working(false); }
  }

  async function complete(current: ImportBatch) {
    setBatch(current); setRecovery(false); setOriginalBatchId('');
    setNotice(`${current.confirmedCount}件の${recordName}を登録しました。確定済みの取込を再送しても二重登録されません。`);
    await onConfirmed?.();
  }

  async function recover(id = batch?.id) {
    if (!id) return;
    working(true); setError(''); setNotice('');
    try {
      const current = await api.getImportBatch(id);
      await showBatch(current);
      setRecovery(false);
      if (current.status === 'CONFIRMED') await complete(current);
      else setNotice('取込状態を確認しました。まだ確定されていません。内容を確認してから確定してください。');
    } catch (failure) { report(failure); }
    finally { working(false); }
  }

  async function confirm() {
    if (!batch || !confirmationEnabled) return;
    working(true); setError(''); setNotice('');
    let result: ImportBatch;
    try { result = await api.confirmImport(session, batch.id, { version: batch.version }); }
    catch (failure) {
      if (failure instanceof ApiError && (failure.status === 0 || failure.status >= 500)) {
        setRecovery(true);
        try {
          const current = await api.getImportBatch(batch.id);
          if (current.status === 'CONFIRMED') await complete(current);
          else {
            await showBatch(current); setRecovery(false);
            setError('確定の応答を受け取れませんでした。現在は未確定です。内容を確認してから、もう一度確定してください。');
          }
        } catch (lookupFailure) { report(lookupFailure); }
      } else {
        if (failure instanceof ApiError && ['IMPORT_EXPIRED', 'IMPORT_INVALID', 'IMPORT_REFERENCE_CONFLICT', 'IMPORT_ALREADY_CONFIRMED'].includes(failure.code ?? '')) setBlockedBatchId(batch.id);
        if (failure instanceof ApiError && failure.code === 'VERSION_CONFLICT') setRecovery(true);
        if (failure instanceof ApiError && failure.code === 'IMPORT_ALREADY_CONFIRMED') {
          setOriginalBatchId(failure.details.find((item) => item.field === 'batchId')?.message ?? '');
        }
        report(failure);
      }
      working(false); return;
    }
    try { await complete(result); }
    catch (failure) { report(failure); }
    finally { working(false); }
  }

  async function nextPage(kind: 'rows' | 'errors', cursor: number) {
    if (!batch) return;
    working(true); setError('');
    try {
      if (kind === 'rows') {
        const page = await api.listImportRows(batch.id, cursor); setRows(page.items); setNextRow(page.nextCursor);
      } else {
        const page = await api.listImportErrors(batch.id, cursor); setErrors(page.items); setNextError(page.nextCursor);
        errorHeading.current?.focus();
      }
    } catch (failure) { report(failure); }
    finally { working(false); }
  }

  async function showRecords() {
    working(true); setError('');
    try { await onShowRecords?.(); }
    catch (failure) { report(failure); }
    finally { working(false); }
  }

  const expired = batch !== null && Date.parse(batch.expiresAt) <= Date.now();
  const canConfirm = confirmationEnabled && batch?.status === 'PREVIEW_VALID' && batch.id !== blockedBatchId && viewed && !recovery && !expired;

  return <section className="panel import-panel" aria-labelledby="import-title">
    <div className="panel-heading"><span className="step-number">CSV</span><div><h2 id="import-title">{invoice && !confirmationEnabled ? '請求CSVをプレビュー' : `${recordName}CSVを取り込む`}</h2><p>{invoice ? '顧客を先に登録してください。請求書番号は変更できず、取消後も再利用できません。' : '新規顧客を一括登録します。既存顧客は更新しません。'}エラーが1件でもあれば、正常な行も含めて登録されません。</p></div></div>
    <ol className="import-steps"><li>テンプレートに入力</li><li>プレビューで確認・修正</li><li>全件を確定</li></ol>
    <p className="import-guide">UTF-8（BOM可）、ヘッダーは <code>{invoice ? 'customer_code,invoice_number,issue_date,due_date,amount' : 'customer_code,customer_name'}</code> の順です。{invoice ? '日付はYYYY-MM-DD、金額は税込の正の円整数、支払期日は請求日以降です。' : '顧客コードは50文字、顧客名は200文字まで。前後の空白は個別登録と同様に除きます。'}10MiB・10,000行まで取り込めます。</p>
    {!confirmationEnabled && <p className="import-guide">プレビューで内容を確認できます。請求の確定登録はまだ利用できません。</p>}
    <button type="button" className="secondary-button" disabled={busy} onClick={() => download('template')}>テンプレートをダウンロード</button>
    <form className="import-upload" onSubmit={preview}>
      <label htmlFor={`${kind}-csv`}>{recordName}CSVファイル<input id={`${kind}-csv`} type="file" accept=".csv,text/csv" disabled={busy || recovery} onChange={(event) => {
        setFile(event.target.files?.[0] ?? null); setBatch(null); setRows([]); setErrors([]);
        setError(''); setNotice(''); setViewed(false); setRecovery(false); setOriginalBatchId('');
      }} /></label>
      <button className="primary-button" disabled={busy || recovery || !file}>{busy ? '処理中…' : 'CSVをプレビュー'}</button>
    </form>
    {error && <p className="error" role="alert">{error}</p>}
    {notice && <p className="success" role="status">{notice}</p>}
    {batch?.originalBatchId && <p className="import-guide">同じ内容のCSVはすでに確定されています。新しく取り込む前に、元の取込結果を確認してください。</p>}
    {originalBatchId && <button type="button" className="secondary-button" disabled={busy} onClick={() => recover(originalBatchId)}>元の取込結果を確認</button>}
    {batch && <div className="import-preview" aria-labelledby="preview-title">
      <h3 id="preview-title">{batch.status === 'CONFIRMED' ? '取込結果' : 'プレビュー内容'}</h3>
      <p className="import-summary">データ {batch.rowCount}行 / エラー {batch.errorCount}件</p>
      <p className="import-guide">{batch.status === 'CONFIRMED' ? `確定済み：${batch.confirmedCount}件登録` : `まだ1件も登録されていません。プレビュー有効期限：${new Date(batch.expiresAt).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo' })}（日本時間）`}</p>
      {batch.status !== 'CONFIRMED' && expired && <p className="error">有効期限が切れています。CSVをもう一度プレビューしてください。</p>}
      {batch.errorCount > 0 && <section aria-labelledby="import-errors-title">
        <h4 id="import-errors-title" tabIndex={-1} ref={errorHeading}>修正が必要な項目</h4>
        <p className="import-guide">元のCSVを修正して、ファイルを選び直し再プレビューしてください。エラー一覧は修正の説明用です。そのまま再取込する形式ではありません。</p>
        <button type="button" className="secondary-button" disabled={busy} onClick={() => download('errors')}>全エラーをCSVでダウンロード</button>
        <div className="table-scroll"><table aria-label="CSVエラー一覧"><thead><tr><th scope="col">行番号</th><th scope="col">項目</th><th scope="col">入力値</th><th scope="col">理由</th><th scope="col">修正方法</th></tr></thead><tbody>{errors.map((item) => <tr key={item.errorNumber}><td>{item.rowNumber}</td><td>{item.field}</td><td className="csv-input">{item.inputValue || '（空欄）'}{item.inputTruncated && <small>（先頭200文字を表示）</small>}</td><td>{item.reason}</td><td>{item.correction}</td></tr>)}</tbody></table></div>
        <div className="import-pagination"><span>全{batch.errorCount}件中 {errors[0]?.errorNumber ?? 0}〜{errors.at(-1)?.errorNumber ?? 0}件を表示</span><button type="button" className="text-button" disabled={busy || (errors[0]?.errorNumber ?? 0) <= 1} onClick={() => nextPage('errors', 0)}>最初のエラーへ</button><button type="button" className="secondary-button" disabled={busy || nextError === 0} onClick={() => nextPage('errors', nextError)}>次のエラー50件</button></div>
      </section>}
      {rows.length > 0 && <section aria-labelledby="import-rows-title">
        <h4 id="import-rows-title">登録する{recordName}の確認</h4>
        <div className="table-scroll"><table aria-label={`${recordName}CSVプレビュー`}><thead><tr><th scope="col">行番号</th>{invoice && <th scope="col">請求書番号</th>}<th scope="col">顧客コード</th><th scope="col">顧客名</th>{invoice && <><th scope="col">請求日</th><th scope="col">支払期日</th><th scope="col">金額（円）</th></>}<th scope="col">確認</th></tr></thead><tbody>{rows.map((row) => <tr key={row.recordNumber}><td>{row.rowNumber}</td>{invoice && <td>{row.invoiceNumber ?? '—'}</td>}<td>{row.customerCode}</td><td>{row.customerName}</td>{invoice && <><td>{row.issueDate ?? '—'}</td><td>{row.dueDate ?? '—'}</td><td>{row.amount ?? '—'}</td></>}<td>{row.valid ? '正常' : '要修正'}</td></tr>)}</tbody></table></div>
        <div className="import-pagination"><span>全{batch.rowCount}行中 {rows[0]?.recordNumber}〜{rows.at(-1)?.recordNumber}行を表示</span><button type="button" className="text-button" disabled={busy || rows[0].recordNumber <= 1} onClick={() => nextPage('rows', 0)}>最初の行へ</button><button type="button" className="secondary-button" disabled={busy || nextRow === 0} onClick={() => nextPage('rows', nextRow)}>次の50行</button></div>
      </section>}
      <div className="button-row">
        {batch.status === 'CONFIRMED' ? onShowRecords && <button type="button" className="primary-button" disabled={busy} onClick={showRecords}>{invoice ? '債権一覧へ' : '顧客一覧へ'}</button> : confirmationEnabled && <button type="button" className="primary-button" disabled={busy || !canConfirm} onClick={confirm}>全{batch.rowCount}件を確定して登録</button>}
        {(!viewed || recovery) && <button type="button" className="secondary-button" disabled={busy} onClick={() => recover()}>{recovery ? '確定結果を確認' : 'プレビューを再取得'}</button>}
      </div>
      {recovery && <p className="import-guide">確定結果が不明です。再送する前に「確定結果を確認」でサーバーの状態を確認してください。</p>}
    </div>}
  </section>;
}
