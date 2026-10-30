# 回収対象と共有対応管理 — Issue #5

請求・実 ledger・充当と履歴に依存する準備 branch。公開統合と画面は後続。

## 対象 read

基準日 asOf は Asia/Tokyo の業務日付として必須。ACTIVE、dueDate<asOf、現在の実残高>0を対象とし、期日当日は対象外。現金と明示手数料を numeric/BigInteger で厳密に控除し、取消済み請求の公開残高は0、原額は保持する。
ACTIONABLE は未保留、HELD は保留、ALL は両方を返す。次回日で対象を隠さない。dueDate/invoiceId の keyset、limit1〜100。詳細と対応履歴は完済・取消後も残す。GET は state 初期化・監査を行わない。

## 保存境界

V10 は shared raw balance view、collection_state と append-only collection_event を追加する。V1〜V9 は変更しない。raw ledger は元額を保ち、回収 read のみ取消済みを実効残高0にする。既存 LedgerRepository・充当・公開残高・請求ガードは変更しない。
collection state は独立 version と保留理由・共有 note・次回日を持つ。未作成は version0。連続 event、tenant付きFK、記録時 actor label と immutable audit を DB 制約で保つ。

## 後続の対応操作

NOTE は本文と必須 nullable nextActionDate を保存する。明示 null は消去、省略は400。HOLD/RELEASE は理由必須で既存 note と予定を保ち、state/event/audit を一 transaction で保存する。完済・取消後も事後メモを許す。
文面は現在の対象・実残高から確認して手でコピーするだけ。候補外は409。宛先メール master、外部送信、送信済み event を追加しない。

## 検証

実 PostgreSQL で日付、現金/手数料/取消、tenant、1万件ページ、競合、監査 rollback、不変履歴を確認する。正常 HTTP は各 runtime と同じ PR の通常 runn book に含め、既存データを残した同じ DB で反復する。独立レビューと最新 main 統合は別 gate。
