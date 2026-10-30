# 請求原本・取消済み一覧と担当者表示

R18/R19/R21、EX-I07/I10、ADR-0002に対応するBE契約の追加。取消を含む原本参照を可能にし、既存のrevision/snapshot/auditを書き換えない。

## 契約

- `GET /api/invoices/{id}/history` の各項目にrequired `actorEmail` を追加する。認証済みtenantと同じ `app_user.email_canonical` の現在値を表示し、無効化済みuserも対象とする。原記録を識別する正本は既存の `actorUserId`。emailの変更は表示に反映し、過去時点のemailを保証するsnapshotではない。
- `GET /api/receivables` は `status=ACTIVE`（省略時の互換default）、`CANCELLED`、`ALL` を受け付ける。各rowにrequired `status=ACTIVE|CANCELLED` を返す。CANCELLEDは原 `amount` を保持し、`outstandingAmount=0`。ACTIVEの既存金額契約は維持する。
- tenant、customerId、dueFrom/dueTo、dueDate/id keyset、limit1〜100を維持する。次pageでも同じstatus/customer/due filterを保持する。他tenantの顧客・原本・履歴は取得できない。
- 既存の日時はUTC offset付きで返し、日付の意味をtimezoneで変換しない。画面でのJST表示と一覧filter・担当者表示は後続FE。新route・migration・indexは追加せず、予約済みV6/V7/V8/V9には触れない。

## 実検証

起点はmain `2cc94688425322de1929caed7deaee4ecb96114a`。初期fd70ff4からの差分は取消UIだけで、BE/契約/migrationは同一だった。専用PG5432 DB `debt_invoice_history`、API8096、pool4、Java25で検証した。

| 検証 | 結果・証拠 |
| --- | --- |
| BE `check bootJar` | 全143 tests・0failure/0error/0skip。work/invoice-history-be.log |
| 新規実PG/HTTP3件 | current email変更＋enabled=falseでも原IDと担当者labelを取得、tenant付きactor FK・越境404、raw revision/audit不変、旧default、CANCELLED/ALL paging、customer/due filter、最大JPY整数、0001/1500/1582/9999年、invalid status/limit/cursorを確認 |
| 1万行・全状態browse | ACTIVE9900/CANCELLED100を重複なく100pagesで取得。browse482ms、先頭page実SQL2.375ms・top-N sort37KiB。既存index利用、新index不要。単一Cloudでの実測でありSLAやそれ以上の規模を保証しない |
| 実HTTP runn | invoice book成功後、既存を含む全9冊成功（0skip/0failure）。work/invoice-history-runn-first.log、work/invoice-history-runn-all.log。自testの一意customerとIDで原本を特定し、他の既存recordの件数・順を仮定しない |
| 既存原本保持 | 初回bookの2請求・4revision・4auditを残した同DBで全冊を実行し、前後の固定subsetは同一。SHA256 `563e2a08fa8bc3a5e2067747e2a2a524c391af44a920e62b6d6f679bfb9f9dca`。work/invoice-history-retention.log とbefore/after JSON |
| FE契約追随 | 生成整合・型・Lint・既存74単体成功。work/invoice-history-fe.log。既存レスポンス見本2件へactorEmail/statusのデータだけを追加し、assertionやUI本体は変更していない |
| 運用 | capacity ≥1GiBを確認。lock一致の既存node_modulesを読取再利用し、npm installなし。failure-loop check・diff検査成功 |

Java interface/DTOとTypeScript型は正本OpenAPIから生成した。BE検証後のbase更新はFE-onlyなので、BEを同一sourceのまま維持した。生成物の手編集、DB初期化、既存migration変更、既存原本/監査削除は行っていない。

新UIのbrowser E2E・独立レビュー・GitHub CIは未実施。auth phase隔離の基盤はこのsliceへ取り込んでいない。後続FEでrequired fieldの表示・全状態filter・JST表示を扱う。
