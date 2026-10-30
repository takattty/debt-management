# GitHub upstream transient guard

GitHubの上流HTTPエラーは、権限拒否と結果不明の更新操作から区別する。対象はread-only GETと、更新後の事実確認であり、GitHubの必須checkやmerge条件を緩めない。

## 実観測

| event | 実際に観測した結果 |
| --- | --- |
| publisher-history-runtime-pr-create-502-20261030 | head `641e33860bae5eceb22e028361a5d455720f0abb` のbranch push後、PR作成のGraphQLがHTTP502。正確なhead branchとstate=allを指定したGETが空配列であることを確認してから構造化REST POSTを行い、PR #83を作成した |
| publisher-history-runtime-quality-get-500-20261030 | 別のread-only quality GET `commits/641e33860bae5eceb22e028361a5d455720f0abb/check-runs?per_page=100` がHTTP500となりwatchが停止した。tool chunk `c197a9` |

作成502の正本は `/workspace/debt-publish-allocation-history-runtime/work/pr-create-502-observation.json`（409byte、SHA256 `9e8a2e2f2fa32575b68e20f507cc446881276dfe6f021f8c5680a4459ead9b4a`）。GET500の正本は同ownerの `work/quality-get-500-observation.json`（652byte、SHA256 `8d3085b5cd90910f7be99b52569d64192b5ff83461909ab3b346afd386b82ee6`）。この担当者がGitHubで障害を再誘発したものではない。

## 操作前のguard

1. GETだけを自動再試行の対象にする。HTTP500・502・503・504に限り、初回を含む合計最大3回、待機は2秒・4秒とする。watcher全体の期限を延長し続けない。成功した完全な応答だけをJSONとして検査する。ここで制限するのは回数と待機であり、通信全体のwallclock時間上限を実証したものではない。
2. 403、settings/billingの拒否、認証・契約・権限不足をこのretryへ流さない。その他のstatusや原因不明の失敗も対象外とし、既存の拒否を再試行で回避しない。空配列と取得失敗を同一視しない。
3. POST・PATCH・merge等の結果が不明なら同じ更新を自動再送しない。成功したGETで対象のhead・body・状態を期待値と照合してから、必要な操作だけを再開する。GETの上限到達やmetadata不明のままmergeしない。
4. PR作成ではexact owner/head branch、head SHA、base、bodyを確認する。全ページを確認したGETで一致するPRが既にあればその番号を使い、二重作成しない。空配列を完全に確認できた場合だけ、同じ期待内容による必要な作成へ進む。不一致や曖昧な候補は停止する。
5. mergeの結果不明では、同じheadのPRのmerged状態・merge commitをGETで確認する。未mergeの場合も最新head/base・必須check・独立レビュー・本文条件を取り直す。失敗したGETを未mergeの証拠として使わない。
6. 各試行のstatus・回数・対象ID・証拠pathだけをbounded metadataとして表示する。完全な応答・失敗証拠はworkへ保存し、raw bodyや切断応答を成功扱いしない。

## 保存先と検証の境界

`scripts/failure-loop.py` の `ROOT` はscript自身の所有checkoutであり、既定storeとguard参照もそのrootを使う。cwdだけを別treeへ変えて旧scriptを実行しない。今回のcanonical記録は専用treeのscriptを絶対pathで実行し、mainの全key・event・resolution prefixを保持する。

業務code・API・DB・runtimeは変更しない。operator guardはこの文書とlearningへの導線として保存する。publisherのprivate watcherは別のwork artifactであり、その改修・fake probeの結果と、この担当者の照合を区別する。GitHub操作・障害再現・heavy buildは実施しない。

## 実施した検証

| 検証 | 結果・証拠 |
| --- | --- |
| canonical保存 | 実2件を新key `github-upstream-transient` に保存し、open2・check exit1を先に確認。`work/canonical-open-check.log` |
| publisherのprivate helper | `/workspace/debt-publish-payment-confirm/work/safe_github_get.py` のSHA256 `3830cac4cd0fb79e766b6a3e4074e3e0759bdce7c14bd9372cb9bc7b50032d09` を受領proofと照合。GETだけを明示し、POST/mergeの自動retryはない |
| 提供されたfake probes | publisherの8ケースが全PASS、実通信0・実sleep0。正本は `work/github-get-retry-stub-proof.json` |
| 作者による追加検査 | 同一helperを架空runner/sleeperで13ケース検査。500/502成功、503/504→成功、3回停止、403/401/404/501/429/未知statusの即停止、不正JSON、上限外attempts/endpointの拒否が全PASS。全callは明示GET、実通信0・実sleep0。`work/author-private-helper-proof.json` |
| private callerの境界 | watch-quality / post-independent-review / finalize-reviewed-prはGETをhelperへ接続し、POST/PATCH/mergeは別callのまま。静的照合であり、これらの実行はしていない |
| 元履歴と最終check | main30keys/92eventsの全entry・順序を保持し、新key2eventsだけ追加して31keys/94events。実検証後のresolve/checkが成功し、`/tmp` cwdから専用treeの絶対scriptを実行したcheckも成功。`work/final-prefix-proof.json` / `work/final-failure-loop.log` / `work/foreign-cwd-check.log` |

fake probesはproviderの実障害やPR作成・mergeのE2Eではない。private helperをrepositoryへ取り込んだ変更でもなく、通信timeoutや実環境の回復時間は未検証である。API・PG・E2E・FE/BE buildは文書のみの今回のscopeでは実行しない。
