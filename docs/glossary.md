# Ubiquitous language

| 用語 | 定義・区別 |
| --- | --- |
| Tenant | 契約企業。すべての業務データと利用者所属の分離単位 |
| User | 管理者または経理担当者。1 人は 1 Tenant に所属し、取引先はログインしない |
| Administrator | メンバー招待を行う Tenant の管理者 |
| Accountant | 請求・入金・消込・回収の業務担当者。初期版では取込・消込・取消を単独確定可 |
| Customer | 債権の取引先。Tenant 内の CustomerCode で識別する |
| CustomerCode | CSV と画面で顧客を対応付ける必須コード。異なる Tenant では同じ値を持ち得る |
| Invoice | 外部作成された請求書の管理記録。PDF を発行する機能ではない |
| InvoiceNumber | Tenant 内で一意の必須番号。取消しても監査のため履歴を保持する |
| Receivable | Invoice に基づく未回収の売掛債権。Invoice 総額と回収済み額から残高を求める |
| DueDate | 支払期日。期日当日は期限超過ではなく、翌日以降の未回収分を Overdue とする |
| Payment | 銀行明細 CSV 等から登録した入金事実。振込名義は顧客本人との一致を保証しない |
| PayerName | 入金の振込名義。顧客との Alias 対応は候補理由として使用し、無断で確定しない |
| Allocation | 入金額の一部を一つ以上の Invoice に充当する確定記録 |
| AllocationLine | 一つの Invoice に対する充当額と担当者が明示した手数料差額 |
| MatchingCandidate | 未充当入金と未回収請求を結び付ける提案。理由を持つが確定記録ではない |
| UnallocatedBalance | 入金額から有効な現金充当額を引いた未充当残高。過入金も保持する |
| OutstandingBalance | Invoice 額から有効な現金充当額と明示手数料額を引いた未回収残高 |
| BankFee | 担当者が明示した振込手数料差額。入金額を増やすものではない |
| Reversal | 確定済み取引の効果を取り消す新たな記録。原記録の削除や上書きではない |
| ImportPreview | 取込対象・解析済み値・検証結果を固定した確定前の提案 |
| ImportBatch | CSV の取込単位。全件確定または全件拒否し、重複確定を防止する |
| Overdue | 基準日より前の DueDate を持ち、OutstandingBalance が正の状態 |
| CollectionCase | 期限超過の債権に対する回収活動。対応履歴、次回対応日、保留理由を持つ |
| CollectionHold | 一時的な督促保留。債権残高や期限超過の事実を消さない |
| AuditEvent | 誰が・いつ・何を・どの Tenant/対象へ行ったかを記録する監査証跡 |
| Version | 競合検知のためにクライアントが提示する対象の更新版。古い版の変更は拒否する |
| LoanPrincipal | 売掛債権とは区別する貸付金元本。貸付先・貸付日・当初元本・追加貸付・元本返済・取消履歴を管理し、利息計算は対象外 |

金額の単位は JPY の整数。`amount`、`cashAmount`、`feeAmount` 等の名前だけで意味を曖昧にせず、API とモデルにその金額がどの残高へ作用するかを示す。
