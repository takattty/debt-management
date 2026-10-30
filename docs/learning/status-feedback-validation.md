# Playwright status feedback guard

共有画面の成功通知とloading通知が同時に存在し、roleだけで1要素と仮定したため、別の実行で3回strict mode違反を観測した。

| 保存した実event | 観測内容 |
| --- | --- |
| pr47-correction-status-strict-831-20261030 | 請求訂正成功と一覧loadingが一致。旧correction treeの `work/independent-e2e-831-failed.log` で13件中12成功・1失敗を直接照合済み |
| guide-cancellation-status-strict-20261030 | guide取消時に既存訂正成功と一覧loadingが一致。guide treeの `work/guide-browser-initial-failed.log` とsourceを直接照合済み。取消自体はcommit済みのためGETで回復確認した |
| allocation-e2e-recovery-status-scope-20261030 | financial FE準備の応答消失回復後に候補と一覧loadingが一致。`debt-settlement-frontend/work/settlement-fe-20261030T0833-e2e.log` に全21件中19成功・2失敗のうち、このstatus競合1件を保存した |

`statusFeedback(Page | Locator, string | RegExp)` はrole=statusを期待文言で絞る3行のhelperで、既存のexpectと期待文言を維持する。同文言が複数ある場合は所有regionをscopeにする。`.first()`や固定sleepで曖昧さを隠さない。

必須lintのE2E専用AST ruleがhelper以外の直接status selectorを拒否する。既にfilterされた直書き・computed呼出し・静的templateも対象にし、Page/region helper・helper本体・product/unitの許可をNode4testsで検査する。今回の変更は現在mainの既存E2Eのselector移行だけで、金融UI・API契約・backend・生成物は変更しない。

現main `803489a2f8ec6730d21fd384bbe9093338e497de` の既存23keys/63events/resolutionsを全件一致で保持し、`playwright-status-selector-ambiguity` の正確な3eventsだけをunionした。結果は24keys/66events。初回7eでの20keys/60events保存に加え、rebase後も `work/status-main-0916-rebase-proof.json` で全既存record一致を確認した。他のfinancial準備の失敗keyは含めない。

## 検証対象

実行対象sourceは `39488e9`。依存物は保護されたacceptance treeのnode_modulesを読取再利用し、npm ciで変更していない。

| 検証 | 結果・所有work証拠 |
| --- | --- |
| 必須lintとguard | 成功。直書き/filter付き/computed/statictemplateを拒否し、Page/region helper・helper本体・product/unitを許可するNode4tests。`work/status-main-0912-static.log` |
| 型・既存単体・生成整合・build | 成功。Vitest80件、生成差分0。同static log |
| 実API/DBの既存browserフロー | 15件成功。`work/status-main-20261030T0913-e2e.log` |
| production native BFCache | 2件成功。同E2E log |
| baseline保持 | 正規APIで用意した古い52請求×2tenantの前後fingerprint一致。`work/status-main-20261030T0913-baseline.json` と `work/status-main-0913-harness.py` |

API setup→同一JAR/DBの正常再起動→browser/nativeを直列実行し、backend/Viteは同親でwaitして正常停止した。固定sleep・429再送・DB cleanupは行っていない。ログ、HTML、結果はphase固有pathへ新規作成し上書きしない。

runtimeには独立レビュー済み `7a311c23d776ae7477a0fda9a083ff589d513a71` のJARを使用する。全tree OIDは `05f9de7f1432795f4092754042fb32b617ee0d82`、JAR SHA256は `e287b2389b23beb92027c82d3bea72ce100ec7f45a90502a8cddf32bbd97777e`。7eと7aのfrontend/contracts差分は0だが、7aにはpayment previewのbackend変更があるため、7eそのもののruntimeと主張しない。専用架空DBでAPI setup後に同一artifact/DBのbackendを正常再起動してbrowser phaseを分離する。

## main更新後のsource照合

PR60 merge後に803489aへrebaseした。テストした39488e9とrebased headのfrontend/AGENT/Skill差分は0。reviewed7aと新main803のbackend/src/main・frontend・contractsも差分0で、実行したJARは新mainと同じproduction入力を持つ。旧39488e9と結果を含むe0b49d4は `prep/playwright-status-feedback-main-tested` へ固定した。再実行結果の流用ではなく、この完全一致を根拠に同じbrowser suiteを再実行しなかった。
