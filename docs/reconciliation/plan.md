# 入金と消込の段階実装 — Issue #4

Status: 準備実装。Issue #3の請求管理に依存し、この文書はIssue #4完成の宣言ではない。根拠はR03〜11/R18〜22、EX-M01〜13、ADR-0001/0002。

## 合意済みの業務判断

B04: 正のJPY整数の受取入金のみ。0円・出金・返金は初期対象外。transaction_idはtenant内一意で消込取消後も永久予約する。CSVで既存入金を変更せず、入金訂正・取消機能は追加しない。
B05/Q02: 手動の名義→顧客対応を優先し、未回収額一致、期日の早い順。名義は前後空白だけ除去し、NFC・大小文字・全半角変換なし。未登録・複数一致は曖昧さを示し、必ず人が確定する。

## 依存付きの実装単位

| 段階 | 範囲と受入条件 | 検証 |
| --- | --- | --- |
| 1 入金CSVプレビュー | UTF-8/BOM可、固定header transaction_id,transfer_date,payer_name,amount。日付YYYY-MM-DDの実在日、金額1〜9007199254740991円。10MiB/10000行。template、固定snapshot、専用payment-rows、共通batch/errors/download。V6は入金事実とPAYMENT種別制約 | 正負・日付・名義の単体、実PG tenant/FK/一意性/不変性、runn multipart/CSRF/エラー訂正/越境 |
| 2 原子的確定と入金一覧 | 版・期限・取引ID・semantic fingerprintを再確認し、全入金/監査/batchを一transactionで確定。再送・並行確定・監査失敗rollback。bounded一覧 | 実PG race/rollback/retry、runn全件拒否と確定結果。ledger未導入の間だけ未充当=入金額と明記 |
| 3 手動名義対応 | B05の名義→顧客の登録、active一覧、誤登録の理由/版付き無効化と原対応の詳細。V7はpayer_alias専用 | 共通名義ルール、実PG activepair race/監査rollback/tenant FK、runn ADMIN/ACCOUNTANT・CSRF・未知field・越境 |
| 4 公開残高と請求変更 guard | V8の同snapshot残高とinvoice lock内の実eligibility。現金/手数料1円でも訂正・取消を拒否し、全取消後だけ解禁。[設計](public-read-design.md) | CIの実PG/public read/lock待機/監査rollbackと既存全回帰 |
| 後続 候補・ledger | B05の候補理由、手動確定、複数請求・一部充当・明示手数料・過入金・取消の保存則。ledgerはV8以降 | 実ledgerと請求訂正/取消race、現金/手数料1円でも有効なら訂正拒否、取消後の解禁 |

前後空白以外は変換しない。取引ID100、名義200 code points、制御文字・改行はstrip前に拒否する。日付の将来日制限は合意されておらず追加しない。
semantic fingerprintはkind/formatと長さ付き各値を用い、BOM・改行・行順に依存せず、重複行の個数を保持する。予約はCONFIRMEDだけで、プレビューは予約しない。
正常行と不正行が混在しても業務入金・監査はゼロ。エラーは元CSVの開始物理行・項目・入力値・理由・修正方法を示し、formula-safe CSVで取得する。

## 契約と責務

顧客/請求のImportRowResponseは維持し、入金は正本OpenAPIのPaymentImportRowResponseからJava/TypeScriptを生成する。GET /api/imports/{id}/payment-rowsは入金専用。共通rowsは入金に409、他tenantには404。共通batch/errorsを再利用し、段階2のconfirmはkindごとの確定ユースケースへdispatchし、他tenantには404を返す。
paymentはtenant/取引ID/振込日/名義/金額/source batch/actor/記録時刻を持ち、更新・削除をDBでも拒否する。source batchとactorをtenant付きFKで検証する。顧客対応や可変残高を入金事実へ混ぜない。

入金取込・名義対応だけではdebt.reconciliation.enabledを有効化しない。公開残高・請求変更guardでInvoiceMutationEligibilityを実V8 adapterへ置換し、公開commandの有効化とは分離する。V7を名義対応に予約し、ledger設計はV8以降の別段階。手数料と顧客横断充当の業務例は後続ledger段階で扱い、このプレビューには含めない。

段階2はGET /api/paymentsをtransfer_date,id順・limit1〜100で取得し、任意のdateFrom/dateToを含むkeysetページングで読む。記録者・記録時刻・sourceBatchIdを返す。公開残高ではunallocatedAmountを有効cash充当から導出し、原本金額と分離する。入金事実や監査に部分成功を残さず、期限がtransaction途中に切れた場合もrollbackする。

## 手動名義対応の技術選択（段階3）

ADMIN/ACCOUNTANTは認証済みtenantの顧客へ名義を手動登録する。名義はCSVと同じ `PaymentValues.text(value, 200)` を呼び、制御文字・改行等を除去前に拒否し、前後空白以外を変換しない。同じ名義を複数顧客へ登録できるため、候補の曖昧さは後続で扱う。同tenantの同名義・同顧客active pairだけをpartial unique indexで拒否する。登録時点では候補も残高も変えない。

誤登録はversion/reason付きで一度だけ無効化し、原名義・顧客・登録者/時刻を変更しない。無効化者/時刻/理由と監査は同transactionで保存し、物理削除・復活・再無効化を拒否する。無効化後の同pair登録は、新しい手動判断を新IDの行として追加する技術選択とし、原行と両操作の監査を保持する。無効化履歴を再取得する最小GET詳細を含め、active一覧はID keyset・limit1〜100で読む。全操作の顧客・actorをtenant付きFKでも検証する。
