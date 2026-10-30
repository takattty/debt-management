# 督促・対応管理の準備 — Issue #5

根拠は [Issue #1](https://github.com/takattty/debt-management/issues/1)、[Issue #5](https://github.com/takattty/debt-management/issues/5)、R13〜15、EX-C01〜05。依存は請求・V8内部ledger（base `3394555`）。Issue #4未完了のため準備branchであり、業務機能完成・公開統合を意味しない。

## Slice と API

| Slice | API / storage | 受入条件 |
| --- | --- | --- |
| 1 候補・詳細・履歴 | `GET /api/collections?asOf=YYYY-MM-DD&view=ACTIONABLE/HELD/ALL&cursor=...&limit=...`、`GET /api/collections/{invoiceId}?asOf=...`、`GET /api/collections/{invoiceId}/history`。V9 collection_state / collection_event | active invoice、実active cash+feeを控除したoutstanding>0、dueDate<asOf。dueDate,id keyset、limit1〜100。完済・取消は候補から除外し、詳細・履歴は残す |
| 2 対応・保留・文面 | `POST /api/collections/{invoiceId}/actions`、`/hold`、`/release`、`GET /api/collections/{invoiceId}/draft?asOf=...` | shared note / nextActionDate、明示hold/release理由、独立version、actor・監査を同transaction。文面は現在値から確認・コピーするpreviewだけ |

すべて認証済みtenantで分離し、ADMIN/ACCOUNTANTが操作する。正本OpenAPIからJava interface/DTOとTypeScriptを生成する。既存GETの残高や `InvoiceMutationEligibility`、reconciliation有効化flagは変更しない。B06/B07のcommand policyも追加しない。

## 基準日・残高・状態

`asOf` はAPIで必須、tenantの業務日付（Asia/Tokyo）を利用者側が明示する。serverのUTC日付を暗黙に採用しない。期日当日は超過0・候補外、翌日は超過1。固定の滞留bucket・督促頻度は追加しない。

金額集計はV8と同じ有効なCONFIRM（REVERSEがないもの）のcash / explicit feeを分離し、numeric / BigIntegerで厳密に計算する。残高は入力・編集項目にしない。ledgerが空の場合だけ全額未消込となり、partial / 完済 / reversalを次の取得で反映する。一つのSQL snapshotで候補と現在の残高・stateを読む。

heldは残高を変更しない。通常ACTIONABLEとHELDを分け、ALLでもheldと理由を返す。完済・取消になってもstate/eventを削除せず、保留を自動解除しない。次回対応日が過去・未来で候補を自動除外しない。次回対応日もYYYY-MM-DD、1〜9999年の実在日とし、未合意の未来日制限は置かない。

## V9 の責任

`collection_state` は `(tenant_id,invoice_id)` ごとのheld / holdReason / latestNote / nextActionDate / 独立versionを持つ。未作成はversion0・未保留。invoice factsとledgerを更新しない。

`collection_event` は対応NOTE、HOLD、RELEASEの追記履歴。invoice、連続version、actor ID・記録時の同tenant識別label、時刻、note / 理由、変更後のnextActionDate / held / holdReasonを保持する。UPDATE/DELETEをDBで拒否する。stateも履歴と不整合な変更を拒否する。tenant付きFKとversion一意制約を置く。

更新は認証済みactorのenabled/所属をDBで再確認し、collection_stateをlazy初期化してrow lock、期待versionを比較する。state、event、auditを同transactionで保存し、監査失敗時は全部rollbackする。collection stateだけをlockし、invoice/payment ledgerのlock順序へ割り込まない。対象invoiceとactorの参照はFK/共有lockで存在・所属を保つ。

## 対応操作の表現

対応NOTEは共有の記録本文とnextActionDateを保存する。nextActionDateは必須・nullableで、現予定日/変更日/明示nullによる消去を呼出し側が選ぶ。member省略は400でstate/event/auditを変えない。生成DTOは手編集せず、限定RequestBodyAdviceでpresenceも検査する。HOLD/RELEASEは理由必須、既存note/nextActionDateを維持する。held中もNOTEを記録できる。完済・取消後も事後の対応メモを残せるが、候補には戻さない。保留中への再HOLD、未保留へのRELEASEはstate conflictとし、古いversionも409で返す。

## 文面と業務例

EX-C01: 2026-11-30期限・60000円に対し、asOf11-30は候補外、12-01は1日超過。EX-C02: 担当者交代後もnote・次回日・actor label/UUID・時刻を取得。EX-C03: hold理由「入金確認待ち」を保留一覧へ表示し、release理由もeventへ追記。EX-C04: 完済・取消は候補外、reversalは実残高に従い再判定し、対応履歴を残す。

EX-C05: 顧客宛名、請求番号、期日、現在未回収額と基準日を含む件名/本文をpreviewする。宛先・内容を人が確認してコピーする用途で、受信メールアドレスmasterを追加しない。候補外の請求には督促draftを作らず409、保留中は保留情報を伴う確認用preview。外部送信・送信済みという虚偽event・メールAPI/課金・自動送信は追加しない。

## 検証・未実施の境界

単体と実PGで日付境界、partial/全額/手数料/reversal、完済・取消履歴保持、keyset、tenantFK、actor識別、同version競合、監査fault rollback、event不変を検証する。runnは実HTTP/正本response検証で認証/権限/CSRF・strict ISO・操作と原履歴を確認する。各sliceの固定SHA、実施済/検証済/未実施を返す。公開UIと画面E2E・独立レビュー・依存順統合はこのBE準備の完了に含めない。
