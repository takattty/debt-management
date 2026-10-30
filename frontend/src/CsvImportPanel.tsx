import { useEffect, useRef, useState, type FormEvent } from 'react';
import { formatJstTimestamp } from './timestamp-presentation';
import { api, ApiError, type ImportBatch, type ImportError, type ImportRow, type PaymentImportRow, type Session } from './api';

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

type PreviewView = { batch: ImportBatch | null; rows: ImportRow[]; paymentRows: PaymentImportRow[]; errors: ImportError[]; nextRow: number; nextError: number; viewed: boolean };
const emptyView = (): PreviewView => ({ batch: null, rows: [], paymentRows: [], errors: [], nextRow: 0, nextError: 0, viewed: false });
const initialPanel = () => ({ file: null as File | null, view: emptyView(), recovery: false, retryBatchId: '', originalBatchId: '', blockedBatchId: '', busy: false, error: '', notice: '' });
type PanelState = ReturnType<typeof initialPanel>;

export default function CsvImportPanel(props: Props) {
  const { session } = props;
  return <CsvImportWorkspace key={[props.kind ?? 'CUSTOMER', session.tenantId, session.userId, session.csrfToken].join(':')} {...props} />;
}

function CsvImportWorkspace({ kind = 'CUSTOMER', confirmationEnabled = true, session, onSessionExpired, onBusyChange, onConfirmed, onShowRecords }: Props) {
  const invoice = kind === 'INVOICE';
  const payment = kind === 'PAYMENT';
  const settings = {
    CUSTOMER: { recordName: '顧客', header: 'customer_code,customer_name', description: '新規顧客を一括登録します。既存顧客は更新しません。', format: '顧客コードは50文字、顧客名は200文字まで。前後の空白は個別登録と同様に除きます。', filename: 'customers-template.csv', template: api.downloadCustomerImportTemplate, preview: api.previewCustomerImport },
    INVOICE: { recordName: '請求', header: 'customer_code,invoice_number,issue_date,due_date,amount', description: '顧客を先に登録してください。請求書番号は変更できず、取消後も再利用できません。', format: '日付はYYYY-MM-DD、金額は税込の正の円整数、支払期日は請求日以降です。', filename: 'invoices-template.csv', template: api.downloadInvoiceImportTemplate, preview: api.previewInvoiceImport },
    PAYMENT: { recordName: '入金', header: 'transaction_id,transfer_date,payer_name,amount', description: '受取入金の事実を確認します。取引IDは契約企業内で一意とし、一度使うと再利用できません。', format: '振込日はYYYY-MM-DD、金額は1〜9,007,199,254,740,991円の整数です。0円・出金・返金は対象外です。振込名義は前後空白のみ除きます。架空銀行明細用の固定形式で、銀行固有のCSV形式ではありません。', filename: 'payments-template.csv', template: api.downloadPaymentImportTemplate, preview: api.previewPaymentImport },
  }[kind];
  const { recordName } = settings;
  const [panel, setPanel] = useState(initialPanel);
  const { file, recovery, retryBatchId, originalBatchId, blockedBatchId, busy, error, notice } = panel;
  const { batch, rows, paymentRows, errors, nextRow, nextError, viewed } = panel.view;
  const displayedRows = payment ? paymentRows : rows;
  const errorHeading = useRef<HTMLHeadingElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const generation = useRef(0);
  const active = useRef(true);
  const running = useRef(false);
  const handlers = useRef({ onBusyChange, onSessionExpired, onConfirmed, onShowRecords });
  useEffect(() => { handlers.current = { onBusyChange, onSessionExpired, onConfirmed, onShowRecords }; }, [onBusyChange, onSessionExpired, onConfirmed, onShowRecords]);
  useEffect(() => {
    generation.current++; active.current = true;
    return () => {
      active.current = false;
      if (running.current) handlers.current.onBusyChange(false);
      running.current = false;
    };
  }, []);
  useEffect(() => { if (batch && batch.errorCount > 0) errorHeading.current?.focus(); }, [batch]);

  function current(operation: number) { return active.current && generation.current === operation; }
  function update(operation: number, change: (state: PanelState) => PanelState) {
    if (!current(operation)) return;
    setPanel((state) => current(operation) ? change(state) : state);
  }
  function report(failure: unknown, operation: number) {
    if (!current(operation)) return;
    if (failure instanceof ApiError && (failure.status === 401 || failure.code === 'CSRF_INVALID')) {
      active.current = false; generation.current++;
      if (running.current) handlers.current.onBusyChange(false);
      running.current = false;
      if (fileInput.current) fileInput.current.value = '';
      setPanel({ ...initialPanel(), error: failure.message });
      handlers.current.onSessionExpired(failure);
    } else update(operation, (state) => ({ ...state, error: failure instanceof Error ? failure.message : '処理に失敗しました。もう一度お試しください。' }));
  }
  async function perform(action: (operation: number) => Promise<void>) {
    if (!active.current || running.current) return;
    const operation = ++generation.current;
    running.current = true; handlers.current.onBusyChange(true);
    update(operation, (state) => ({ ...state, busy: true, error: '' }));
    try { await action(operation); }
    catch (failure) { report(failure, operation); }
    finally {
      if (current(operation)) {
        running.current = false; update(operation, (state) => ({ ...state, busy: false }));
        handlers.current.onBusyChange(false);
      }
    }
  }
  function chooseFile(selected: File | null) {
    if (!active.current) return;
    const operation = ++generation.current;
    if (running.current) handlers.current.onBusyChange(false);
    running.current = false;
    update(operation, () => ({ ...initialPanel(), file: selected }));
  }
  function validateKind(value: ImportBatch) {
    if (value.kind !== kind) throw new ApiError(400, '取込形式が一致しません。CSVをもう一度プレビューしてください。', 'INVALID_REQUEST');
  }
  // Reads are pure: a failed sibling must never leave a late setter running.
  async function readRows(id: string, cursor = 0) {
    if (payment) {
      const page = await api.listPaymentImportRows(id, cursor);
      return { rows: [] as ImportRow[], paymentRows: page.items, nextRow: page.nextCursor };
    }
    const page = await api.listImportRows(id, cursor);
    return { rows: page.items, paymentRows: [] as PaymentImportRow[], nextRow: page.nextCursor };
  }
  async function showBatch(value: ImportBatch, operation: number) {
    if (!current(operation)) return false;
    validateKind(value);
    const [rowPage, errorPage] = await Promise.all([readRows(value.id), api.listImportErrors(value.id)]);
    if (!current(operation)) return false;
    const view: PreviewView = { batch: value, ...rowPage, errors: errorPage.items, nextError: errorPage.nextCursor, viewed: true };
    update(operation, (state) => ({ ...state, view, retryBatchId: '', originalBatchId: value.originalBatchId ?? '' }));
    return true;
  }

  async function download(target: 'template' | 'errors') {
    await perform(async (operation) => {
      const blob = target === 'template' ? await settings.template() : batch ? await api.downloadImportErrors(batch.id) : null;
      if (blob && current(operation)) saveCsv(blob, target === 'template' ? settings.filename : 'import-errors.csv');
    });
  }
  async function preview(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file) {
      if (active.current && !running.current) { const operation = ++generation.current; update(operation, (state) => ({ ...state, error: 'プレビューするCSVファイルを選択してください。' })); }
      return;
    }
    await perform(async (operation) => {
      update(operation, (state) => ({ ...state, view: emptyView(), recovery: false, retryBatchId: '', originalBatchId: '', notice: '' }));
      const value = await settings.preview(session, file);
      if (!current(operation)) return;
      validateKind(value);
      update(operation, (state) => ({ ...state, retryBatchId: value.id }));
      await showBatch(value, operation);
    });
  }
  async function complete(value: ImportBatch, operation: number) {
    if (!current(operation)) return;
    validateKind(value);
    update(operation, (state) => state.view.batch?.id !== value.id ? state : ({ ...state, view: { ...state.view, batch: value }, recovery: false, originalBatchId: '', notice: `${value.confirmedCount}件の${recordName}を登録しました。確定済みの取込を再送しても二重登録されません。` }));
    if (current(operation)) await handlers.current.onConfirmed?.();
  }
  async function recover(id = batch?.id ?? retryBatchId) {
    if (!id) return;
    await perform(async (operation) => {
      update(operation, (state) => ({ ...state, view: emptyView(), retryBatchId: id, notice: '' }));
      const value = await api.getImportBatch(id);
      if (!await showBatch(value, operation)) return;
      update(operation, (state) => ({ ...state, recovery: false }));
      if (value.status === 'CONFIRMED') await complete(value, operation);
      else update(operation, (state) => ({ ...state, notice: '取込状態を確認しました。まだ確定されていません。内容を確認してから確定してください。' }));
    });
  }
  async function confirm() {
    if (!batch || !canConfirm) return;
    const target = batch;
    await perform(async (operation) => {
      update(operation, (state) => ({ ...state, notice: '' }));
      let result: ImportBatch;
      try { result = await api.confirmImport(session, target.id, { version: target.version }); }
      catch (failure) {
        if (!current(operation)) return;
        if (failure instanceof ApiError && (failure.status === 0 || failure.status >= 500)) {
          update(operation, (state) => ({ ...state, recovery: true }));
          const value = await api.getImportBatch(target.id);
          if (!current(operation)) return;
          if (value.status === 'CONFIRMED') await complete(value, operation);
          else if (await showBatch(value, operation)) update(operation, (state) => ({ ...state, recovery: false, error: '確定の応答を受け取れませんでした。現在は未確定です。内容を確認してから、もう一度確定してください。' }));
        } else {
          if (failure instanceof ApiError) update(operation, (state) => ({ ...state,
            blockedBatchId: ['IMPORT_EXPIRED', 'IMPORT_INVALID', 'IMPORT_REFERENCE_CONFLICT', 'IMPORT_ALREADY_CONFIRMED'].includes(failure.code ?? '') ? target.id : state.blockedBatchId,
            recovery: failure.code === 'VERSION_CONFLICT' || state.recovery,
            originalBatchId: failure.code === 'IMPORT_ALREADY_CONFIRMED' ? failure.details.find((item) => item.field === 'batchId')?.message ?? '' : state.originalBatchId,
          }));
          if (failure instanceof ApiError && failure.code === 'IMPORT_REFERENCE_CONFLICT') await showBatch(await api.getImportBatch(target.id), operation);
          report(failure, operation);
        }
        return;
      }
      await complete(result, operation);
    });
  }
  async function nextPage(target: 'rows' | 'errors', cursor: number) {
    if (!batch) return;
    const id = batch.id;
    await perform(async (operation) => {
      if (target === 'rows') {
        const rowPage = await readRows(id, cursor);
        update(operation, (state) => state.view.batch?.id === id ? { ...state, view: { ...state.view, ...rowPage } } : state);
      } else {
        const page = await api.listImportErrors(id, cursor);
        update(operation, (state) => state.view.batch?.id === id ? { ...state, view: { ...state.view, errors: page.items, nextError: page.nextCursor } } : state);
        if (current(operation)) errorHeading.current?.focus();
      }
    });
  }
  async function showRecords() {
    await perform(async (operation) => { if (current(operation)) await handlers.current.onShowRecords?.(); });
  }

  const expired = batch !== null && Date.parse(batch.expiresAt) <= Date.now();
  const canConfirm = confirmationEnabled && batch?.status === 'PREVIEW_VALID' && batch.id !== blockedBatchId && viewed && !recovery && !expired;

  return <section className="panel import-panel" aria-labelledby="import-title">
    <div className="panel-heading"><span className="step-number">CSV</span><div><h2 id="import-title">{!confirmationEnabled ? `${recordName}CSVをプレビュー` : `${recordName}CSVを取り込む`}</h2><p>{settings.description}エラーが1件でもあれば、正常な行も含めて登録されません。</p></div></div>
    <ol className="import-steps"><li>テンプレートに入力</li><li>プレビューで確認・修正</li><li>全件を確定</li></ol>
    <p className="import-guide">UTF-8（BOM可）、ヘッダーは <code>{settings.header}</code> の順です。{settings.format}10MiB・10,000行まで取り込めます。</p>
    {!confirmationEnabled && <p className="import-guide">プレビューで内容を確認できます。{recordName}の確定登録はまだ利用できません。</p>}
    <button type="button" className="secondary-button" disabled={busy} onClick={() => download('template')}>テンプレートをダウンロード</button>
    <form className="import-upload" onSubmit={preview}>
      <label htmlFor={`${kind}-csv`}>{recordName}CSVファイル<input id={`${kind}-csv`} type="file" accept=".csv,text/csv" disabled={busy || recovery} ref={fileInput} onChange={(event) => chooseFile(event.target.files?.[0] ?? null)} /></label>
      <button className="primary-button" disabled={busy || recovery || !file}>{busy ? '処理中…' : 'CSVをプレビュー'}</button>
    </form>
    {error && <p className="error" role="alert">{error}</p>}
    {notice && <p className="success" role="status">{notice}</p>}
    {!batch && retryBatchId && <button type="button" className="secondary-button" disabled={busy} onClick={() => recover(retryBatchId)}>{recovery ? '確定結果を確認' : 'プレビューを再取得'}</button>}
    {batch?.originalBatchId && <p className="import-guide">同じ内容のCSVはすでに確定されています。新しく取り込む前に、元の取込結果を確認してください。</p>}
    {originalBatchId && <button type="button" className="secondary-button" disabled={busy} onClick={() => recover(originalBatchId)}>元の取込結果を確認</button>}
    {batch && <div className="import-preview" aria-labelledby="preview-title">
      <h3 id="preview-title">{batch.status === 'CONFIRMED' ? '取込結果' : 'プレビュー内容'}</h3>
      <p className="import-summary">データ {batch.rowCount}行 / エラー {batch.errorCount}件</p>
      <p className="import-guide">{batch.status === 'CONFIRMED' ? `確定済み：${batch.confirmedCount}件登録` : `まだ1件も登録されていません。プレビュー有効期限：${formatJstTimestamp(batch.expiresAt)}`}</p>
      {batch.status !== 'CONFIRMED' && expired && <p className="error">有効期限が切れています。CSVをもう一度プレビューしてください。</p>}
      {batch.errorCount > 0 && <section aria-labelledby="import-errors-title">
        <h4 id="import-errors-title" tabIndex={-1} ref={errorHeading}>修正が必要な項目</h4>
        <p className="import-guide">元のCSVを修正して、ファイルを選び直し再プレビューしてください。エラー一覧は修正の説明用です。そのまま再取込する形式ではありません。</p>
        <button type="button" className="secondary-button" disabled={busy} onClick={() => download('errors')}>全エラーをCSVでダウンロード</button>
        <div className="table-scroll"><table aria-label="CSVエラー一覧"><thead><tr><th scope="col">行番号</th><th scope="col">項目</th><th scope="col">入力値</th><th scope="col">理由</th><th scope="col">修正方法</th></tr></thead><tbody>{errors.map((item) => <tr key={item.errorNumber}><td>{item.rowNumber}</td><td>{item.field}</td><td className="csv-input">{item.inputValue || '（空欄）'}{item.inputTruncated && <small>（先頭200文字を表示）</small>}</td><td>{item.reason}</td><td>{item.correction}</td></tr>)}</tbody></table></div>
        <div className="import-pagination"><span>全{batch.errorCount}件中 {errors[0]?.errorNumber ?? 0}〜{errors.at(-1)?.errorNumber ?? 0}件を表示</span><button type="button" className="text-button" disabled={busy || (errors[0]?.errorNumber ?? 0) <= 1} onClick={() => nextPage('errors', 0)}>最初のエラーへ</button><button type="button" className="secondary-button" disabled={busy || nextError === 0} onClick={() => nextPage('errors', nextError)}>次のエラー50件</button></div>
      </section>}
      {displayedRows.length > 0 && <section aria-labelledby="import-rows-title">
        <h4 id="import-rows-title">登録する{recordName}の確認</h4>
        <div className="table-scroll">{payment ? <PaymentRowsTable rows={paymentRows} /> : <table aria-label={`${recordName}CSVプレビュー`}><thead><tr><th scope="col">行番号</th>{invoice && <th scope="col">請求書番号</th>}<th scope="col">顧客コード</th><th scope="col">顧客名</th>{invoice && <><th scope="col">請求日</th><th scope="col">支払期日</th><th scope="col">金額（円）</th></>}<th scope="col">確認</th></tr></thead><tbody>{rows.map((row) => <tr key={row.recordNumber}><td>{row.rowNumber}</td>{invoice && <td>{row.invoiceNumber ?? '—'}</td>}<td>{row.customerCode}</td><td>{row.customerName}</td>{invoice && <><td>{row.issueDate ?? '—'}</td><td>{row.dueDate ?? '—'}</td><td>{row.amount ?? '—'}</td></>}<td>{row.valid ? '正常' : '要修正'}</td></tr>)}</tbody></table>}</div>
        <div className="import-pagination"><span>全{batch.rowCount}行中 {displayedRows[0]?.recordNumber}〜{displayedRows.at(-1)?.recordNumber}行を表示</span><button type="button" className="text-button" disabled={busy || displayedRows[0].recordNumber <= 1} onClick={() => nextPage('rows', 0)}>最初の行へ</button><button type="button" className="secondary-button" disabled={busy || nextRow === 0} onClick={() => nextPage('rows', nextRow)}>次の50行</button></div>
      </section>}
      <div className="button-row">
        {batch.status === 'CONFIRMED' ? onShowRecords && <button type="button" className="primary-button" disabled={busy} onClick={showRecords}>{payment ? '入金一覧へ' : invoice ? '債権一覧へ' : '顧客一覧へ'}</button> : confirmationEnabled && <button type="button" className="primary-button" disabled={busy || !canConfirm} onClick={confirm}>全{batch.rowCount}件を確定して登録</button>}
        {(!viewed || recovery) && <button type="button" className="secondary-button" disabled={busy} onClick={() => recover()}>{recovery ? '確定結果を確認' : 'プレビューを再取得'}</button>}
      </div>
      {recovery && <p className="import-guide">確定結果が不明です。再送する前に「確定結果を確認」でサーバーの状態を確認してください。</p>}
    </div>}
  </section>;
}

function PaymentRowsTable({ rows }: { rows: PaymentImportRow[] }) {
  return <table aria-label="入金CSVプレビュー"><thead><tr><th scope="col">行番号</th><th scope="col">取引ID</th><th scope="col">振込日</th><th scope="col">振込名義</th><th scope="col">金額（円）</th><th scope="col">確認</th></tr></thead><tbody>{rows.map((row) => <tr key={row.recordNumber}><td>{row.rowNumber}</td><td>{row.transactionId}</td><td>{row.transferDate}</td><td>{row.payerName}</td><td>{row.amount}</td><td>{row.valid ? '正常' : '要修正'}</td></tr>)}</tbody></table>;
}
