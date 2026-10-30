# B05 read-only 消込候補の契約

Status: read-only API sliceを実装。Issue #4全体の完成ではない。B05/Q02、EX-M01/M06/M13、ADR-0001/0002とV8 ledger-designを根拠にする。候補の表示だけであり、command・引当・監査writeは含めず、既存実public read/eligibilityとreconciliation flagを変更しない。

## 意味と並び順

`GET /api/payments/{id}/candidates` は認証済みtenantの入金に対し、同tenantのACTIVEかつ現在のoutstanding > 0の請求を返す。優先順は次のtupleで固定する。

1. 入金名義と一致するactive手動対応の顧客の請求を先にする。
2. そのgroup内で、現在のinvoice outstandingとpayment unallocated cashが完全一致する請求を先にする。
3. そのgroup内でdueDate昇順、最後にDBのUUID順。

対応外の顧客、将来期日、金額不一致の請求も隠さない。`manualPayerMatch` / `exactAmountMatch` と日付を理由として返す。金額一致だけで顧客を決めず、複数の対応がある場合も一つを選ばない。

対応状態はactive対応のdistinct customer数から `UNREGISTERED` / `UNIQUE` / `MULTIPLE_CUSTOMERS` として明示する。対象請求の有無と対応登録の有無は区別する。UNIQUEでも `requiresHumanConfirmation=true` とし、候補は必ず人が確認する。

payment unallocated cashが0なら `cashCandidateStatus=NO_UNALLOCATED_CASH` とitems空を返す。これは現金候補の表示状態であり、操作の許可ではない。B06の同tenant顧客横断、B07の同confirm内手数料とreverse/reconfirmを満たす公開commandは別段階。

## 名義・金額・snapshot

名義の共通処理は `PaymentValues.text(value, 200)`。前後空白だけを除去し、NFC・大小文字・全半角・カナfoldは行わない。immutable paymentの名義をJava共通関数で確認した後、aliasの比較もC collationの完全一致にする。

残高・payment state version・invoice version・alias状態・順位は一つのSQL statement snapshotで読む。V8のCONFIRM lineからREVERSE済みoperationを除き、paymentは現金だけ、invoiceは現金と明示feeを差し引く。PostgreSQL numeric / Java BigIntegerで集計し、LedgerBalancesの保存則も確認する。未作成payment stateはversion0。stateのlazy作成、lock、auditなどのwriteは行わない。

Collection V9のbalance VIEWは使わず、V8 tableだけを参照する。既存migration・LedgerRepositoryの独立計算・RC write guard・payment immutable triggerは変更しない。新SQLはLedgerRepositoryの独立計算とランダムなcash/fee/reversal列で照合する。

金額は原入金/請求のJS safe整数上限内の数値で返す。expectedPaymentVersion / expectedInvoiceVersionはDB bigint全域を丸めず渡せる十進stringとする。将来commandが入力を受ける形は今回決めない。

## pageと更新の境界

limit1〜100、server発行cursorはformat版・payment ID・理由rank2値・dueDate・invoice IDを含む。違うpaymentのcursor、不正形式/rank/日付/UUID/limitは400。cursorは読取位置であり認可情報ではなく、tenantと現在残高の条件を各queryで再適用する。missing/他tenantのpaymentは404。rank tupleのkeysetでpageを読む。

pageごとにfresh snapshotを読み、page間のledger/alias変更を凍結したと保証しない。表示時のexpected versionと理由を返し、将来の人の確定前に最新状態を再読する必要がある。read-only候補を確定の資格や許可として扱わない。

## 今回の移植と検証境界

固定元ae04の新規production/PG/runnと候補fixtureだけを移し、既存payment bookのtenant修正67、public read、実invoice guard、固定V8と全学習を保持する。旧185件/12冊と独立レビューは旧sourceの背景証拠であり、新SHAのPASSへ流用しない。

新target/full、独立計算・40snapshot・real10k、通常13冊の反復、GET前後のdatasource保持は所有treeのworkへ別名で保存する。公開routeのCI根拠は通常BE checkの実PG回帰と通常globのpayment-candidates.yml。UI・browser E2E・公開commandsは含めない。
