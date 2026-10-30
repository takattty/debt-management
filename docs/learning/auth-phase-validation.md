# Authentication window isolation between verification phases

2026-10-30、起点main `177d4ffdfd9fb6e7ddd7c61b7523b602623dc56e`。業務API・DB・FE・OpenAPI・本番limiterの変更はない。

## 観測と原因

| event | 実際の証拠 |
| --- | --- |
| alias-review-runn-repeat-rate-limit-20261030 | reviewerの同runtimeで全runnを続けて実行し、2回目はalias/payment/unicodeのloginがHTTP429、全11冊中3冊失敗。所有tree review-payer-alias-fixed、work/review-fixed-all-runn-rate-limited.log |
| alias-ui-runn-e2e-window-sharing-20261030 | UI ownerの同runtimeでrunnとE2Eを並行実行すると16pass/6fail、login後の画面到達待ちで失敗。work/alias-fixed-e2e-concurrent.log。HTTP429のnetwork traceは保存していないため、直接観測した429と区別する。runn終了・自然経過後の単独22件は成功 |

`LoginAttemptLimiter` はprocess内でIP単位60回/60秒を数え、成功loginもIP試行を消さない。APIの正常/異常系とbrowser検証が同じcounterを共有すると、業務目的と関係のないtraffic総量で検証が止まる。既存2件を確認し、失敗数を増やすための再誘発は行っていない。

## guardと否定検証

verifyはAPI/runn/proxy後にbackendの生存を確認して正常停止し、port解放・容量・artifact SHA256を検査してから同じDBで再起動する。browser phase後も生存・artifact・正常停止を確認する。API/browserそれぞれのlogを保存し、失敗時のcleanupは元の終了値を保持する。limiterの閾値/window、CSRF、DBを変更せず、認証windowを待つ固定sleepや429再送を追加していない。

| 検証 | 結果 |
| --- | --- |
| 実verify + fake backend processes | 7 tests成功。2lifecyclesと両PID回収、restart失敗、E2E終了値23保持、前/後backend死活、artifact差替え、port残留、restart容量不足、不正shutdown、ready timeoutを検査 |
| 既存capacity/全Python | capacity6件を含む全37 tests成功。work/phase-python.log |
| shell・差分 | bash構文、git diff --check成功 |

## 実sequenceとsource proof

検証用harnessは最終verifyの内部lifecycle関数をそのまま抽出して実行した。build全体やPG全suiteを実行したとは主張しない。関数block SHA256は `6fbb50519c60715876c468462c9804f8db7284850597b7d930970b0b3a531e8c`、work/phase-source-proof.jsonへ保存した。

backendは既存alias377 artifactのSHA256 `d7e57f18d972a3351e4ea4bd22ae4e48093f3a924c51d7d3af0446a363b822d2` を両phaseで使用し、専用DB debt_payer_alias/port8096/pool4を維持した。FEは固定 `2e43f717c6d634e0520693151f9547b8f583d961` と同一のsourceをworkへcopyして依存だけを読取再利用した。業務source・契約を編集していない。

| 順序 | 実結果・証拠 |
| --- | --- |
| API phase | PID690451でrunn全11冊・0skip/0failure。work/phase-runn.log |
| proxy phase | 本番と同じNginx設定/imageでupstream portだけ専用8096へ向けた相当検証1冊・0skip/0failure。work/phase-proxy.log |
| phase境界 | 正常停止・port解放後、同一artifactでPID690900へ再起動。再起動前の容量1.27GiBでpass |
| browser phase | 元の全22件に、API phaseで作られた対応を再起動後にbrowserで取得・reloadし、POST再作成なしで確認する1件を追加。計23件成功、native BFCache2件成功。work/phase-e2e.log |
| DB保持 | 元14alias/関連audit18のSHA256はAPI後・再起動後・browser後すべて `525e732b686d177370603b6006b599d272b0c6068614f04eb5d9d51e0e9e2c9a`。新しいAPI phase行も再起動とbrowser後で不変。work/phase-acceptance.log、phase-before/api/restart/browser.json |
| 終了 | 両backend PIDを停止。DB・artifact・log・証拠を保持 |

実sequenceの初回harnessは、proxy用generatorを未導入の古いalias checkoutへ指定して停止した。API後のEXIT cleanupは正常に働いた。新checkoutでcatalogを再観測し、proxyのhelperとbookを同checkoutへ固定した後に上記sequenceを完走した。既存worktree-path-assumptionへ9件目のeventを保存し、canonical `scripts/verify-proxy.sh` の自己cwd固定と実proxy成功をguardに再度resolveする。歴史や失敗を隠すDB初期化は行っていない。

GitHub CI・独立レビュー、BE再build/PG全suiteは未実施。別runtimeのCompose再起動証拠を今回のphase検証へ流用していない。
