# B05 read-only 消込候補の契約

Status: read-only API sliceを実装。Issue #4全体の完成ではない。B05/Q02、EX-M01/M06/M13、ADR-0001/0002とV8 ledger-designを根拠にする。候補の表示だけであり、command・引当・監査write・eligibility adapter・reconciliation有効化は含めない。

## 意味と並び順

`GET /api/payments/{id}/candidates` は認証済みtenantの入金に対し、同tenantのACTIVEかつ現在のoutstanding > 0の請求を返す。優先順は次のtupleで固定する。

1. 入金名義と一致するactive手動対応の顧客の請求を先にする。
2. そのgroup内で、現在のinvoice outstandingとpayment unallocated cashが完全一致する請求を先にする。
3. そのgroup内でdueDate昇順、最後にDBのUUID順。

対応外の顧客、将来期日、金額不一致の請求も隠さない。`manualPayerMatch` / `exactAmountMatch` と日付を理由として返す。金額一致だけで顧客を決めず、複数の対応がある場合も一つを選ばない。

対応状態はactive対応のdistinct customer数から `UNREGISTERED` / `UNIQUE` / `MULTIPLE_CUSTOMERS` として明示する。対象請求の有無と対応登録の有無は区別する。UNIQUEでも `requiresHumanConfirmation=true` とし、候補は必ず人が確認する。

payment unallocated cashが0なら `cashCandidateStatus=NO_UNALLOCATED_CASH` とitems空を返す。これは現金候補の表示状態だけであり、未回答B07のfee-only操作を許可/拒否するpolicyではない。B06顧客横断充当とB07後付手数料は未回答のまま維持する。

## 名義・金額・snapshot

名義の共通処理は `PaymentValues.text(value, 200)`。前後空白だけを除去し、NFC・大小文字・全半角・カナfoldは行わない。immutable paymentの名義をJava共通関数で確認した後、aliasの比較もC collationの完全一致にする。

残高・payment state version・invoice version・alias状態・順位は一つのSQL statement snapshotで読む。V8のCONFIRM lineからREVERSE済みoperationを除き、paymentは現金だけ、invoiceは現金と明示feeを差し引く。PostgreSQL numeric / Java BigIntegerで集計し、LedgerBalancesの保存則も確認する。未作成payment stateはversion0。stateのlazy作成、lock、auditなどのwriteは行わない。

Collection V9のbalance VIEWは使わず、V8 tableだけを参照する。既存migration・LedgerRepositoryの独立計算・RC write guard・payment immutable triggerは変更しない。新SQLはLedgerRepositoryの独立計算とランダムなcash/fee/reversal列で照合する。

金額は原入金/請求のJS safe整数上限内の数値で返す。expectedPaymentVersion / expectedInvoiceVersionはDB bigint全域を丸めず渡せる十進stringとする。将来commandが入力を受ける形は今回決めない。

## pageと更新の境界

limit1〜100、server発行cursorはformat版・payment ID・理由rank2値・dueDate・invoice IDを含む。違うpaymentのcursor、不正形式/rank/日付/UUID/limitは400。cursorは読取位置であり認可情報ではなく、tenantと現在残高の条件を各queryで再適用する。missing/他tenantのpaymentは404。rank tupleのkeysetでpageを読む。

pageごとにfresh snapshotを読み、page間のledger/alias変更を凍結したと保証しない。表示時のexpected versionと理由を返し、将来の人の確定前に最新状態を再読する必要がある。read-only候補を確定の資格や許可として扱わない。

## 受入と未実施

起点は `675da6dc88d8ce0d6fa95fcb21c4923e8da46869`。専用DB debt_candidates、API8096/pool4で実施した。Java本番は新3file・163行。正本からJava interface/DTOとTypeScript型を生成した。

| 検証 | 実結果・証拠 |
| --- | --- |
| BE `check bootJar` | 全185 tests・0failure/0error/0skip。work/candidates-be.log |
| 新規10件 | cursor2件と実PG/HTTP8件。名前6variants、manual→現在額→期日/UUID順、cursor/limit、tenant/401/404、partial/fee/全額/reversal/取消完済除外、古日付、JSsafe最大額、Long.MAX_VALUE版string、state未作成とread前後7table/audit不変を検証 |
| 独立計算との対照 | 2payment/20invoices/40回の固定seedランダムcash・fee・reversalでLedgerRepositoryの独立SUMと全残高/版を比較。別paymentの明示feeも候補に反映 |
| snapshot競合 | 20回のcommit済CONFIRM/REVERSEと40回の並行readでpayment/invoiceの残高・版が同じ状態を示すことを確認 |
| 1万請求 | 重複なく100pagesで全件取得、1571ms。単一Cloud・このdatasetの実測でSLAではない |
| 実runn | 新book30steps（HTTP26件）単独成功後、既存を含む全12冊・0skip/0failure。work/candidates-runn-first.log、work/candidates-runn-all.log。role別cookieとown fixture ID/keysetを明示し、empty DB/全体先頭を仮定しない |
| 既存原本保持 | 初回bookの2invoice/1payment/2alias/6auditを残した同DBで全冊後も固定subset不変。SHA256 `ad51933482dc75892e942dff27a86920ac209a5ea0e6b1f4cad4b469dde9a920`。work/candidates-retention.log、before/after JSON |
| FE型と整合 | 正本検証・生成整合・typecheck・lint成功。work/candidates-fe.log。npm installなし、lock一致の既存依存を読取再利用 |
| 運用 | capacity ≥1GiB、failure-loop check・diff検査成功。JUnitのimmutable fixture初期化は専用test DBだけで、実HTTPの既存recordは削除していない |

UIは別担当で、新APIの本番UI E2E・独立レビュー・GitHub CIは未実施。既存GETの残高、write guard、payment immutable、migration、V9 VIEW、feature flag、command policyに変更はない。
