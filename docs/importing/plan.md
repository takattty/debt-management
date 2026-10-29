# Receivables implementation plan — Issue #3

Status: 段階導入中。CSV codecは #23、受動ImportBatch metadata/DB制約は #25で統合済み。顧客CSVのtemplate/preview/保存状態・行・error取得/downloadを最初のAPI slice、原子的confirmを次のsliceとし、画面は後続sliceへ分ける。Issue #1/#3、requirements R01〜05/R12/R18〜22、EX-I01〜11、ADR-0001/0002を根拠とする。
前提: #2のemail session/role/tenant/auditが成立済み。全PRで既存BE/FE単体・統合・runn API・E2Eを含む必須CIと同SHA独立レビューを維持する。

## 合意済みの業務判断

- 顧客CSV=`customer_code,customer_name`、請求CSV=`customer_code,invoice_number,issue_date,due_date,amount`。
- UTF-8、BOM可、headerあり、日付`YYYY-MM-DD`、金額は税込JPY整数。顧客を先に登録する。
- 顧客コード/請求番号は必須、請求番号はtenant内一意。外部請求書の記録でありPDF発行なし。
- preview→修正/再取込→明示confirm。不正行があれば正常行も一切登録しない。担当者は単独確定可。
- 未消込請求のみ訂正/取消可。消込後は消込取消を先に行い、変更・取消履歴を残す。

| 合意済みの業務判断 | 具体例 | 2026-10-29のユーザー回答 |
| --- | --- | --- |
| B01 顧客CSVの既存code | C001「架空商事」へCSVで「架空商事 新名称」 | 新規専用でバッチ全拒否。更新は既存version付き個別画面で明示 |
| B02 請求番号訂正/取消後再使用 | INV001取消→新規INV001 | 番号は登録後不変・取消後も予約。未消込なら顧客/日付/金額は理由付き訂正可 |
| B03 金額/日付の業務範囲 | 0円/負額の返品、issue_dateより前のdue_date | 初期は税込の正の円整数、期日は発行日以降。0円・負額・返品/値引伝票は初期対象外 |

3点ともユーザーが「3点とも推奨案で進める」と回答済み。回答はIssue #3にも保存した。これらについて再確認を求めず、顧客CSVから実装を進める。

## ユースケースと不変条件

1. `PreviewCustomerImport` / `PreviewInvoiceImport`: アップロードを解析し、固定snapshotと全errorを保存する。業務Customer/Invoiceはまだ変更しない。「取込ゼロ」は業務行ゼロを意味し、preview/error記録の保存と区別する。
2. `ConfirmImport`: tenant・actor・preview版・期限・参照・重複を再検証して、一つのtransactionで全件とauditを作る。
3. `ListReceivables`: 有効請求、顧客、金額、未回収額、期日をページ取得。変更後はDBから再取得する。
4. `CorrectInvoice`: 未消込・有効状態・期待versionを確認し、新しいrevisionとauditを原子的に保存する。
5. `CancelInvoice`: 同じ制約を検証し、取消理由・時刻・actorを保存する。履歴は削除しない。

- InvoiceとCustomer、ImportBatchとactorの参照は同じtenant。body/headerでtenantを指定させない。
- `invoiceAmount = activeCashAllocations + activeExplicitFees + outstandingBalance`。初期は全額未回収で、paidフラグを設けない。
- preview/confirmエラーで業務行の一部だけが増えない。confirm再送と同時confirmで二重生成しない。
- 確定済みbatchのsnapshotを書換えない。訂正は個別ユースケースとrevisionで表現し、CSV再送を暗黙の訂正にしない。
- versionは条件付き更新、revision番号は単調増加。古い画面の保存を409で拒否して再読込を促す。
- 取消済みInvoiceは未回収一覧・将来の消込候補・督促候補から除外するが、履歴閲覧には残す。

## データモデル案

| Table | 主な列・制約・責務 |
| --- | --- |
| import_batch | id,tenant_id,kind,format_version,raw_hash,semantic_hash,status,version,created_by,created_at,expires_at,confirmed_at,confirmed_by,row_count,error_count,result_count |
| import_row | batch_id,tenant_id,physical_start_line,typed_snapshot,referenced_customer_id,referenced_customer_version。batch/tenant複合FK |
| import_error | batch_id,tenant_id,row_number,field,input_value,reason_code,reason,correction,input_truncated。index(batch_id,row_number,field) |
| invoice | id,tenant_id,customer_id,invoice_number,issue_date,due_date,amount_jpy,version,status,source_batch_id,created_at,updated_at,cancelled_at |
| invoice_revision | invoice_id,tenant_id,revision,operation,snapshot,reason,actor_user_id,occurred_at。UNIQUE(invoice_id,revision) |
| audit_event | 既存のuser UUIDによる監査を再利用。import/Invoice更新と同一transaction |

Customer既存code/name/versionを再利用し、(tenant_id,id)のUNIQUEとInvoiceからの複合FKで越境参照をDBでも拒否する。
InvoiceNumberはB02に従い、取消状態にかかわらずUNIQUE(tenant_id,invoice_number)。
DBはbigint、Javaはchecked integer。APIの各金額はJSで正確に扱える範囲を明示し、CSVをfloat経由で読まない。
技術上の上限案は1件9,007,199,254,740,991円。集計はBigInteger/SQL numericでoverflowを防ぎ、数値JSONで精度を失う合計を返さない。
請求番号100 code points、顧客code50/name200は既存制約に合わせる案。Unicode NFC/NFKCや大小文字の無断正規化をしない。

## CSV解析とpreview

- header名・順序は固定。重複/不足/余分列を一覧で示す。UTF-8の不正byteを置換せず拒否し、先頭BOMのみ許可する。
- RFC4180のquoted field、comma、escaped quote、CRLF/LFを解析する。headerのみはEMPTY_IMPORT、途中の空白行は欠落fieldとして拒否し、無断skipしない。末尾の通常改行は追加行に数えない。
- 業務fieldの改行/制御文字は拒否する案。quoted newlineを正しく解析した後、元ファイルの開始物理行をrowNumberとして修正箇所を示す。
- 顧客fieldは制御文字を除いたうえで既存CustomerValuesの規則を適用する。前後空白は個別登録と同様にstripし、大小文字・Unicodeを正規化しない。不正NULは保存・表示用snapshotで制御文字記号`␀`に置換し、500を出さず元の行・項目を示す。
- 固定日付はstrict ISO LocalDate。JPYは桁区切り/小数/指数表記/通貨記号を拒否し、B03の業務検証を別レイヤーで適用する。
- upload上限10MiB、データ10,000行、cell 2,048 code pointsを技術初期値とし、stream解析で先に制限する。月1万枚はSLAではない。
- field errorの入力値は先頭200 code pointsまで保持し、切詰め表示を明示。全errorsを保存し、画面は50件ずつ、件数と位置を示す。
- malformed CSV/文字コード/上限違反もrow/field/input/reason/correctionの形へ変換し、内部例外やSQLを公開しない。
- customer参照と既存番号はtenant単位でまとめて検索する。10,000行ごとのN+1 queryや全tenant走査を避ける。
- snapshotはserver側typed値と参照版で固定し、confirmに行データを送らせない。preview有効期限は24h（Clock注入）。
- UTF-8 BOM・改行コード・末尾改行だけの違いはsemantic hashを同じにする。row順はhash時にsortして同内容の並替え再取込も検出する。
  同一行が複数あることはsort後も保持し、hash前にdedupしない。formatVersionとkindをhashのscopeに含める。
- raw_hashは診断用。正常に確定した(tenant,kind,formatVersion,semanticHash)だけを重複の基準にし、失敗previewや修正再試行を恒久ブロックしない。
- 原CSV全文をログ/auditへ出さない。raw fileを永続保管しなくてもsnapshotとerrorで再現できる構成を優先する。

## 原子的confirmと再試行

`PREVIEW_VALID → CONFIRMED`。PREVIEW_INVALIDは確定不可。invalid/期限切れ/参照競合の場合は新previewを促し、正常行だけを取り込まない。
confirmはbatch rowをlockし、同tenant/有効actor・version・期限・errorゼロを確認する。同tenantのadmin/accountantがconfirmでき、createdByとconfirmedByを分けて保存する。customer版とinvoice一意性を再検証する。
顧客がpreview後に改名されていた場合も、利用者が見た内容と違うため409で新previewへ誘導する（EX-I06）。
業務行、invoice initial revision、audit、batch CONFIRMEDを同一transactionで保存する。audit失敗を含む例外では全rollbackする。
同batch confirm再送は元の結果を200で返す。同内容の別batchを並行confirmした場合はDB一意制約で一方のみ成功し、他方へ既存取込IDを返す。
別内容のbatchでも同じinvoiceNumberを競合登録した場合は一方のみ成功し、他方は全rollbackして行errorへ戻す。
rollback後の競合error保存は別の明示transactionで行う。失敗の記録保存エラーで元batchが確定したように見せない。
顧客confirmの競合時は業務transactionのrollback完了後、別beanのREQUIRES_NEWで同じbatchをlockする。まだ同じversionのPREVIEW_VALIDの場合だけerrorを保存し、PREVIEW_INVALID/version+1へ遷移する。typed row snapshotは変更しない。失敗記録の保存も失敗すれば元のpreviewを残してエラーを返し、成功扱いにしない。duplicate re-uploadはoriginalBatchIdから元の確定結果を参照できる。
UIはネットワーク応答喪失時にbatch状態GETで結果を確認し、盲目的に新batchを作らない。二重click無効化は補助に留める。

## エラー一覧downloadと修正UX

画面とdownloadは同じerror snapshotを使い、`row_number,field,input_value,reason,correction`を出力する。error codeもAPIに持つ。
Content-Dispositionは固定の安全なfilename、text/csv UTF-8 BOM付き。各cellをquoteし、`"`をescapeする。
入力が`=,+,-,@`、TAB/CR/LF等で始まる場合は、空白の後も検査して先頭apostropheでformulaを無害化する。
quoteだけではspreadsheet formula injectionを防げない。数式文字列を実行しないこと、comma/quote/補助文字の破損がないことを検査する。
画面はHTML escape、inputValueと修正方法を別欄、先頭エラーへfocus、エラー件数と「1件も登録されていない」を明示する。
正常行previewも表示するがconfirm不可。downloadはエラー説明用で、固定upload形式そのものではない旨を表示する。
「元CSVを修正→再アップロード→新preview→確定」の導線を設け、旧errorが新previewへ混ざらないようにする。

## API正本案（ここでは編集しない）

| Method/path | Contractの要点 |
| --- | --- |
| GET /api/imports/customers/template | UTF-8 BOM付きheaderのみ。架空の業務行を実登録させない |
| POST /api/imports/customers/preview | multipart file→201 ImportPreview{batchId,version,status,totalRows,errorCount,expiresAt} |
| POST /api/imports/invoices/preview | 同上。tenant/body actorを受け付けない |
| GET /api/imports/{id} | statusと元の確定結果。別tenantは404 |
| GET /api/imports/{id}/rows?cursor=&limit= | preview正常/不正行の概要をbounded pageで取得 |
| GET /api/imports/{id}/errors?cursor=&limit= | 全errorのpage。row/field/input/reason/correctionを必須化 |
| GET /api/imports/{id}/errors.csv | 同じerror snapshotの安全なdownload |
| POST /api/imports/{id}/confirm | {version}だけ→200 ImportResult。再送も同じresult。行差替え拒否 |
| GET /api/receivables?customerId=&dueFrom=&dueTo=&status=&cursor=&limit= | active/uncollectedをdefault、安定順序(due_date,id)、最大100/page |
| GET /api/invoices/{id} | version/現行snapshot/状態。別tenant404 |
| PUT /api/invoices/{id} | {version,customerId,issueDate,dueDate,amount,reason}、番号は変更入力に含めない |
| POST /api/invoices/{id}/cancel | {version,reason}、再取消409、古いversion409 |
| GET /api/invoices/{id}/history | revision/operation/reason/actor/time、bounded page |

GETは認証必須、全mutationはsession+CSRF。roleはadmin/accountant可。生のtenantやstatus/残高を更新入力として許さない。
生成Java DTO/TypeScript型とrunn request/response検証を正本に追従させる。CSV responseはcontent-typeと原文assertで確認する。

## 将来のSettlementとの接続

Invoice変更はinvoice rowをlockしてから`InvoiceMutationEligibility`でactive settlementを確認する。
#3にはSettlement記録がまだ存在しないことを明示する。#4で実ledger読取へ置換する前に消込機能を有効化しない。
#4のAllocation確定/取消も同じinvoice row lockとversion規約を使い、訂正との競合を直列化する。
既定で何でも通すguardを#4へ持ち越さない。残高はledgerから導出し、担当者が直接編集するDTO/columnを設けない。
#3のport拒否例はstubで制御経路を確認するに留め、実ledgerとの同時競合を検証済みとは報告しない。
#4の必須受入へ「部分消込/手数料が1円でも有効なら訂正不可、取消後のみ可、Allocation対訂正race」を追加する。

## 小さいPRへの依存付き分割

| PR / 依存 | 完結する業務動作 | 追加する意味のある検証 |
| --- | --- | --- |
| R1 顧客CSV縦断 / #2 | template→preview/error download→修正再upload→全件confirm→既存顧客一覧。共通import基盤はこの実例に必要な範囲だけ | 単体:UTF8/BOM/quote/Unicode/行番号/formula。実PG:混在全拒否、confirm再送/並行、audit失敗rollback、tenant否定。runn:multipart→errorsCSV→retry。FE unit/integration:状態遷移、実E2E:架空2行の修正からDB再表示 |
| R2 請求CSVpreview / R1 | 固定請求formatと既存顧客参照、全errorの表示/download、修正再preview。confirm導線は未公開 | 単体:strict日付/円整数/補助文字、B03条件。実PG:未登録/別tenant顧客、同CSV内重複/既存番号、preview中業務行ゼロ。runn:EX-I02/03/04。実E2E:どの行を何に直すかを示し正常previewへ戻る |
| R3 請求confirm＋債権一覧 / R2 | EX-I01〜06、all-or-nothing確定→ページ付き未回収一覧。DBの10k架空請求で検証 | 実PG:重複/競合/audit失敗/応答再送、参照版変更全rollback。runn:confirm/result/tenant別同番号/ページ境界。実E2E:確認内容→確定→再load、二重click。性能:10k正常一括＋最終行不正、EXPLAINとquery数/時間/peak memoryを記録 |
| R4 未消込訂正 / R3 | version＋理由付き訂正→残高/一覧更新→履歴参照（EX-I07/08） | 実PG:二担当者race、audit失敗rollback、revision一意/原snapshot保持、tenantFK拒否。runn:古いversion409/不正型。実E2E:別画面競合→再読込し差分判断。guard拒否経路は単体、実Settlement検証は#4へ明記 |
| R5 取消＋最終回帰 / R4 | 理由付き取消→通常未回収から除外→原請求/取消履歴を参照。B02番号予約を確認 | 実PG:二重取消/古いversion/越境/取消と訂正race、監査保存失敗。runn:cancel/list/history。実E2E:全業務loop、取消フィルタと再利用のエラーUX、Docker再起動後も履歴保持 |

R2はpreview機能だけの段階であるとUI/PRに明記し、Issue #3完了としない。R3が大きい場合は新APIの利用者を壊さない単位で分ける。
各PRは仕様/正本/生成/実装/新検証を同時更新。既存CIをskipしない。並行所有はContract一人、BE import一人、FE一人、検証担当で固定し、migration/auditはBE所有。
10k/月は負荷設計の対象であり未合意のSLAを創作しない。性能合否はbounded query/page、10k成功/失敗時の正しさ、memory上限内完走を基本に実測を保存する。

## 完了と再開の証拠

Issue #3へB01〜03回答、各PR/SHA、R/EX対応、全CI・独立review、実DB/runn/実FE-E2E、10k実測、Docker再起動を記録する。
確認していないSettlement/回収機能を完成扱いにしない。停止時はbranch/SHA/未commit保存先・次コマンド・回答依存箇所を保存する。
