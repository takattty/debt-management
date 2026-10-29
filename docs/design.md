# Domain design

## 課題からユースケースへ

顧客コードと請求番号が Excel 間で揺れると、入金をどの請求へ充当したかを追跡できない。取込時の誤りを修正でき、残高の根拠を保存し、担当者交代後も回収活動を続けられるモデルを優先する。

主要な流れは `RegisterCustomer → PreviewInvoiceImport → ConfirmImport → ListReceivables → PreviewPaymentImport → ConfirmImport → SuggestMatching → ConfirmAllocation → TrackCollection`。訂正は確定状態と有効な消込を確認し、履歴を残す。取消は原記録を保持した逆操作として扱う。

## Bounded Context の仮説

| Context | 所有する責任・モデル | 公開する情報 | 境界の理由 |
| --- | --- | --- | --- |
| IdentityAndAccess | Tenant、User、Membership、Invitation、Session | 認証済み Actor/Tenant、役割 | 契約企業への所属とアクセス判断を業務データから分ける。1 user 1 tenant を中央で保証 |
| Billing | Customer、Invoice、ImportBatch、InvoiceRevision | 請求額・期日・取消状態・請求版 | 外部請求書の記録と訂正を所有。消込のルールを CSV パーサへ混ぜない |
| Settlement | Payment、PayerAlias、MatchingCandidate、Allocation、Reversal | 有効な充当額・手数料額・未充当額 | 入金事実と充当の担当者判断を区別。複数請求への一括入金の整合性を保つ |
| Collections | CollectionCase、CollectionAction、CollectionHold | 次回対応日、保留、対応履歴、文面 | 未回収の事実と回収活動を分ける。保留が債権残高を消さない |
| Lending（後続） | LoanPrincipal、PrincipalMovement | 元本残高 | 売掛金の発生原因・請求訂正と異なる。追加貸付・元本返済・取消を扱い、利息計算を含めない |

これは業務境界であり、当初は一つの Spring アプリと一つの PostgreSQL へ配置する。根拠と代替案は [ADR-0001](adr/0001-modular-monolith.md)。Customer はまず Billing が所有し、Collections 等は ID と必要な表示情報を参照する。汎用 CRM の機能は追加しない。

## Context 間の協調

- IdentityAndAccess が認証済み Actor を生成する。業務 API は body/header の任意 Tenant ID で所属を変更できない。
- Billing は Settlement の有効な充当を確認して請求訂正・取消を制限する。
- Settlement の確定では、入金残高、請求残高、版、Tenant、一意性を同じ DB transaction 内で検証する。Context を分けたために二重充当を許さない。
- Collections は有効な Invoice と充当から導出した未回収を読む。回収活動の履歴は請求完済後も保持する。
- Context 間は明示的な application service / port を介す。別 Context のテーブルへ任意に更新を加える repository は作らない。

後に独立サービス化する場合は現在の強い transaction を失うため、予約・冪等・失敗時補償を改めて設計する。今回その複雑さを先取りしない。

## 不変条件

1. すべての業務識別子と参照は Tenant に属する。関連先の ID が存在するだけでは関連を許可しない。
2. InvoiceNumber は Tenant 内で一意。顧客コードは必須で、顧客を先に登録する。
3. 金額は JPY 整数。Invoice、Payment、充当、手数料、残高の範囲を用途ごとに検証し、負残高や整数 overflow を許さない。
4. `Payment.amount = active cash allocations + unallocated balance`。
5. `Invoice.amount = active cash allocations + active explicit fees + outstanding balance`。
6. 手数料は担当者の明示操作を必要とし、過入金は未充当として保持する。
7. CSV の確認と確定を分け、エラー行が一つでもあればバッチ全体を拒否する。確定時も参照と一意性を再検証する。
8. 同じ CSV の再確定と競合する確定を重複生成しない。
9. 有効な消込のある請求は直接訂正・取消できない。取消は原記録と理由を保存し、二重取消を拒否する。
10. 古い version の更新は拒否する。更新対象が複数ある消込では全対象の競合を検知する。
11. 督促候補は `dueDate < businessDate && outstandingBalance > 0 && invoice is active`。保留は回収活動の状態であり残高は変えない。

## アプリケーションと永続化

`web → application → domain` を基本にし、DB adapter は application の port を実装する。HTTP DTO、CSV DTO、DB row をそのままドメインの更新インターフェースへ露出しない。小規模な縦断基盤で全抽象を先に作る必要はないが、永続化の都合で不変条件を省略しない。

DB の複合一意制約、Tenant を含む外部キー、transaction、version 条件付き更新をアプリケーション検証と組み合わせる。単体テストだけで DB の競合・制約成立を主張しない。監査記録は業務更新と同一 transaction に保存し、認証秘密や CSV の不要な全文をログへ出さない。

認証はローカル起動可能な構成とする。bootstrap 縦断デモの固定 Tenant/ユーザーが存在しても、本番相当の認証やマルチテナント完成の証拠にはしない。開発モードの露出を制限し、認証完了 Issue で実セッションと権限の否定例を検証する。

## CSV とエラー UX

2026-10-29合意の固定形式は UTF-8（BOM可）、ヘッダー必須、金額は税込の円整数（区切りなし）、日付は `YYYY-MM-DD`。顧客は `customer_code,customer_name`、請求は `customer_code,invoice_number,issue_date,due_date,amount`、架空銀行明細は `transaction_id,transfer_date,payer_name,amount` を基本列とする。実装Issueではこの合意に沿って文書とサンプルを合わせる。

エラー DTO は `rowNumber,field,inputValue,reason,correction` を持つ。画面の該当箇所と CSV ダウンロードを同じ結果から生成する。ダウンロード時の spreadsheet formula injection、HTML 表示時の escaping、ファイルサイズ・行数・フィールド長の上限を検証する。利用者に内部例外や SQL を修正方法として見せない。

プレビューは Tenant・actor・形式版・内容 hash・有効期限・解析結果を固定し、確定時にクライアントから行データを差し替えさせない。詳細は [ADR-0002](adr/0002-import-and-ledger-integrity.md)。

## 検証と運用境界

実 PostgreSQL と headless browser を用いて FE→BE→DB を確認する。API、単体、型、Lint はそれぞれ異なる欠陥を見つけるため独立に実行する。入力検証だけでなく誤操作、二重送信、同時更新、Tenant 越境を業務例から検証する。

Cloud 内の検証、GitHub Actions、別コンテキストレビュー、GitHub merge gate は別の証拠である。Actions 未実行を Cloud 検証で置き換えたとは報告しない。契約・権限不足時はブランチと PR を残して merge を止める。[ADR-0003](adr/0003-delivery-and-merge-evidence.md) を参照。
