# 実台帳を使う公開残高

V8 の CONFIRM のうち同一 tenant の REVERSE が存在しない操作だけを集計する。
入金の未充当は原本金額から現金充当を引き、請求の未回収は原本金額から現金充当と明示手数料を引く。
取消済み請求の公開未回収額は 0 とし、原本金額は変更しない。手数料の自動補完はしない。

GET /api/payments、GET /api/receivables、GET /api/invoices/{id} は各ページ・詳細を一つの
SQL statement で読む。原本金額・請求 version・status と numeric 集計が同じ snapshot に属する。
READ COMMITTED で別 statement の請求事実と台帳残高を組み合わせない。ページ間の固定は保証しない。
数値は既存の LedgerBalances の BigInteger 保存則を通し、範囲確認付き long 変換で公開する。
既存の金額上限 9007199254740991、version の transport、cursor/filter、tenant join を維持する。

公開読取は台帳 state 初期化・ロック・監査を行わず、原入金・原操作・履歴を更新しない。
LedgerRepository、候補検索、V8 と READ COMMITTED の DB guard は変更しない。
消込確定・消込取消の公開 command/UI および B06/B07 の未決ポリシーはこの変更の対象外。

実 PostgreSQL の LedgerPublicReadTest は部分・全額・手数料のみ・過入金・全取消、
取消請求の原本表示、tenant 分離、最大安全整数、keyset を確認する。
並行 CONFIRM/REVERSE の version 奇偶と未回収額を照合し、一つの読取で時点を混ぜないことも検証する。


## 請求変更の実台帳 guard

InvoiceMutationConfiguration は V8 の実 LedgerRepository adapter を常時設定し、旧 no-op を提供しない。
public command の flag で無効化できない。台帳依存が欠ければ起動を失敗させる。
InvoiceMutationService の既存順序（有効 actor → invoice FOR UPDATE → version/status → eligibility）を維持し、
ロック待機後に別 statement で fresh な台帳残高を読む。cash または fee が 1 円でもあれば
既存の INVOICE_ALLOCATED / HTTP 409 を返す。amount の増額や日付・顧客だけの訂正も拒否する。
一部の操作を reverse しただけでは解禁せず、有効な全操作の取消後だけ許可する。
adapter は既存 transaction を必須とし、payment lock を追加しないため逆順 lock を作らない。

実 PostgreSQL の LedgerInvoiceMutationTest は実 API の拒否と変更ゼロ、fee-only、複数操作の全取消、
実 pg_stat_activity で同一 invoice lock 待機を確認した上で CONFIRM/REVERSE 後の再判定を検証する。
内部 writer が invoice version を据え置いた場合も guard の可視性を検証し、通常の stale version 拒否は維持する。
取消が先に lock を取った場合の充当 transaction 全 rollback と、監査 trigger の故障による
訂正・取消の version/status/履歴/台帳全 rollback も検証する。既存 V8 の高 isolation 拒否と競合テストを維持する。


専用HTTP probeは `backend/runn/ledger/read-guard.yml`。一意のV8架空fixtureを専用DBへ準備して
残高/fee-only拒否/全取消後訂正をOpenAPI検証する。通常CI wildcardには未組込であり、
将来公開commandsの正規runn/E2Eへ置換してCI必須対象にする。fixture条件と実行方法は同directoryのREADMEを参照。
