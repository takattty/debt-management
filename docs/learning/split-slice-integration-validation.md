# Each delivery slice must stand on its own

完成済みの後続treeが通ることから、前段sliceも成立すると推測しない。各sliceのparent/child、mutation後の再GET、既存E2Eを、対象head単独のmatrixで確認する。必要なhandlerや既存期待値の更新を後続patchへ置き忘れない。

## 同因の2事象

| incident | 実観測と修正 |
| --- | --- |
| financial-candidates-confirmation-e2e-left-in-later-slice-20261030 | PR85のS1で候補入口を有効にしたが、既存payment confirmation E2Eの旧unavailable期待を更新する差分がS2に残った。f00aa719のCI41075606112/job124130828195は23PASS/1FAIL。75389240でexact LATE入金のenabled入口を確認するよう直し、元の日付/金額/重複/tenant assertionsを保持。CI41079019702/job124142828419成功 |
| collection-read-parent-refresh-left-in-actions-slice-20261030 | READ0916は実InvoicePanel訂正/取消後にeditorを閉じるだけで、親の残高を再GETしなかった。独立2casesがRED、後続ACTIONS e994では同2casesがGREENだった。refreshをREADへ前倒しし、各sliceで親の新額/取消状態を確認する永久回帰を追加 |

PR85は既存async描画待ちの3eventsとは別原因であることを既存ledgerとartifactから確認した。READの訂正・取消2casesは同じmissing handlerの1incidentであり、合計は2eventsとする。元のfailed source、raw log、独立RED証拠は保持し、raw GitHub logを再表示しない。

## 別原因のbusy findingは1incident

`collection-read-actions-nested-invoice-parent-busy-gap-20261030` は、子InvoicePanelのbusyがCollectionDetailへ反映されず、実訂正待機中でも親の「対応を閉じる」がREAD/ACTIONS両方で有効だった1incident（独立2control cases）である。split依存の2eventsへ混ぜない。

子busyをDetail・一覧・rootへ伝播し、parent close・選択・navigationを止める。訂正/取消後は親の再GET成功を待ってから子を閉じる。応答不明/再GET失敗では子を自動破棄せず、既存の結果確認操作を保持する。成功後は通常のcloseへ戻す。

このguardは旧80a/6404にも必要な最小のproduction変更である。最終ACTIONSは旧e994のproduction sourceから `frontend/src/CollectionsPanel.tsx` のnested InvoicePanel行だけを変更したexpected blob `ff2b920b56cfb69f5c3c28915e46a50c7afc46ea` と一致させる。DB/JAR/契約を変更せず、rootが更新distで実Nginxの関連E2E/nativeを検証する。

## 単独成立matrix

| scope | 必須確認 |
| --- | --- |
| READ | actions UI無しでも実InvoicePanel訂正/取消→親balance/status/history再GET。pending writeでDetail close・toggle・一覧選択・root busyを維持し、応答不明の結果確認/成功後closeを確認 |
| ACTIONS | 新READへ明示 `--onto` で載せ直し、共通refresh/busyを重複実装しない。READの永久回帰を継承し、note/hold/release/draftの既存検査も保持 |
| real E2E | 各testが新固有fixtureを実APIで作成。実PUT/取消POSTをroute.fetchで送信し、実応答だけを保留してdisabledを確認。解放後の実GETとcloseを検証。mock responseを作らず、finallyでgate/routeをdrain |
| 各head | 既存E2Eを後続slice向けに保留しない。UI/contract/runtimeの可用範囲、tenant、statusFeedback、secret maskingを維持し、未実行のreal E2Eをunit成功と混同しない |

## 検証と証拠

- 独立refresh原本SHA256 `63195fe3730302be693728e7dc9585c0a58e96f697b5539d79556f72dd7ada19` とbusy原本 `bd476d0f45d2cf4a613f7bb608e829093dcc42cf78e96c6fcf53242b542ce258` はreviewer ownerで不変。
- READの永久回帰は旧0916で4RED（refresh2/busy2）、修正後は新4件＋既存read4件の8GREEN。type/lintもroot heavy phase開始前に成功。
- 新real E2E2caseはsource/type/lintを確認した準備であり、作者はbackend/browserを起動・実行していない。rootへ固定blob `b52ab1fc4be8d36655ff317469126f44e3a3e693` を渡した。
- PR85の `ci-slice-compatibility-fix.json` / `after-compatibility-source-proof.json`、failed job metadata、独立binding、RED/GREEN log、source hashesはこのREAD ownerのworkへ保存。
- dependencyは同lockfileのdonorを個別linkし、root node_modulesとcacheを共有symlinkにせず、installを行っていない。各検証前の1GiB gateを保持し、root heavy phase中は新heavy処理を開始しない。

## operatorのowner誤読は既存keyへ別記録

作者がREADの相対work logをACTIONS cwdから一度読もうとしてFileNotFoundとなった事象は、既存 `worktree-path-assumption` の1eventとして別記録する。正しいREAD絶対ownerのhelper＋`--worktree`で解決し、実logのhashと直接読取hashを照合した。source/logの変更やraw log表示はない。別treeの操作と相対work読取を同じcwd依存commandへ混在させない。
