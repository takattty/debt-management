# API conflict guidance regression

独立レビューで、実code `BALANCE_CONFLICT` と `ALLOCATION_ALREADY_REVERSED` の2ケースが顧客コード重複案内へ落ちた。旧7probeの5成功・2REDは `work/settlement-fe-independent-boundary-6ef7e3e-RED.log` に同SHA256で保持する。

productionの409案内を小さな `conflictGuidance` に分離し、既存API clientから使用する。顧客重複案内は実codeとPOST `/api/customers` またはPUT `/api/customers/{id}` の組合せだけに限定する。未知・欠落codeは最新内容確認へ案内し、招待・取込・請求の既存案内を維持する。

消込6codeの文言はレビュー済み `8a537d77ebce77b946378c14c0c6c0177bbb03c5` と全件一致し、`9c045adb5a9b135beb8b0b4045e71f7c8506c662` のAllocationCommandsが送信するcode集合とも一致する。証拠は `work/conflict-guidance-0922-extraction-proof.json` と `work/conflict-guidance-0923-real-code-proof.json`。将来のtransport DTO/API関数を加えず、現clientのHTTP拒否とproduction helperの意味を検査する。

型・lint・既存80＋新25の105unit・生成整合・buildが成功した。証拠は `work/conflict-guidance-0923-static.log`。画面の操作・保存処理は変更せず、今回のE2Eは未再実行。独立レビューとGitHub CIはCoordinator側の後続とする。

学習recordは正確な2実eventだけを追加する。mainの全既存records/events/resolutionsを値・順序とも保持し、financial準備の他の原因keyは含めない。
