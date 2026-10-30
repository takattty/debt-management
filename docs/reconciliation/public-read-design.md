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
消込確定・消込取消の公開 command/UI および B06/B07 を反映する公開command policyはこの変更の対象外。

実 PostgreSQL の LedgerPublicReadTest は部分・全額・手数料のみ・過入金・全取消、
取消請求の原本表示、tenant 分離、最大安全整数、keyset を確認する。
並行 CONFIRM/REVERSE の version 奇偶と未回収額を照合し、一つの読取で時点を混ぜないことも検証する。
