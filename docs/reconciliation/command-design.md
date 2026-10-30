# 消込確定と全体取消

B04 は正のJPY整数の受取入金だけを扱い、transaction_id を tenant 内で永久予約する。
元入金は取消後も変更・削除・再利用しない。
B05 は手動名義対応、未回収額一致、期日順で候補を示し、常に人が確定する。
B06（2026-10-30回答）は一入金から同 tenant の複数顧客への充当を許可する。
B07（同回答）は現金と明示手数料を同じ確定操作に含め、変更時は全体取消後に再確定する。
公開 CONFIRM は現金合計が正であることを要求し、独立した後付手数料操作を提供しない。
一行の現金がゼロでも同じ操作の現金合計が正なら明示手数料を記録できる。

正本 OpenAPI は一入金・最大100請求の POST 確定と原操作全体の POST 取消を定義する。
認証済み tenant と現在有効な ADMIN/ACCOUNTANT を使い、人が requestId、理由、
expectedPaymentVersion と全請求の expectedInvoiceVersion を送る。元入金は不変。
候補の自動確定や顧客一致の必須化を行わない。取消は元操作の請求集合を厳密に照合する。

requestId の tenant 単位 transaction advisory lock の後、payment state、UUID 昇順 invoice を lock する。
異なる入金の同一 requestId も直列化する。同じ actor/path/正規化理由/版/順序非依存 line の
SHA-256 を immutable operation の nullable request_fingerprint に保存する（内部 fixture は null）。
一致する再送は元結果を返し、異なる payload は 409。再送は取消後も同じ結果を返す。
元操作の存在確認にも tenant と payment を含める。他 tenant の参照は 404。

READ COMMITTED で現在残高を BigInteger で検証し、全対象の invoice version と payment version を
overflow を検知して進め、operation・全 line・監査を同一 transaction で保存する。
金額不足・版競合・取消済み・二重取消は全体 rollback。取消は cash と fee を同時に復元する。
invoice_revision は請求事実の履歴であり、消込では追加しない。台帳 operation と監査が履歴を所有する。
HTTP response は immutable 操作を返す。現在の版・残高は既存 GET/candidates を再読込する。

段階分割は契約/生成型、確定 command、取消 command。UI、統合操作履歴画面は後続。
公開 command 有効化だけで Issue #4 の画面受入が完成したとは扱わない。

この準備branchではV9を使用する。未mergeのCollection V9は統合順を確定後V10へ改番し、
既存V8 DBへのupgradeとchecksumを検証してから公開する。released migrationは変更しない。

公開commandの版は候補GETと同じ正確なdecimal stringで送受信する。正規表現とLong.parseLongで
非負bigint範囲を検証し、JS Numberへの変換による丸めを防ぐ。nullの配列要素はmapping前に400で拒否する。
