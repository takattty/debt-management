# Existing invoices baseline

Collection E2Eが「超過1日の自fixtureは未絞込先頭pageにある」と仮定し、既存の2025年請求52件が残るDBで24件中1件失敗した。既存のID/filter/keysetを使う指示だけでは再発を防げなかった。

通常CIのAPI phaseに必須の`backend/runn/zz-existing-invoices.yml`を追加する。Alpha/Betaそれぞれ専用顧客へ、2020年期日の未取消請求52件を正規CSV preview/confirm APIで登録し、50件境界のkeysetと相互tenant分離を確認する。専用顧客と請求番号は毎回UUIDで変え、他の行や過去のbaselineを削除しない。後続E2Eは同じDBを再利用するため、先頭50件に自fixtureがあるという仮定が露呈する。

APIとE2Eは既存verifyの正常再起動でlogin counterのみ分離する。DB、limiter、業務API/契約/migrationは変更しない。

## 実検証

所有treeは`/workspace/debt-existing-invoices-baseline`、branchは`test/existing-invoices-baseline`、baseは`d4bedee8c81377a6ca03fa1f157900fbf95ce390`。新treeから`bootJar`のみ作成し、全BE suiteは再実行していない。本番Java・契約・migration・dependency lockはbaseと同一。

実PostgreSQL専用DB`debt_existing_baseline`、API8096/pool4、Vite5278を使用。FE依存は同一lockの既存treeからread reuseし、追加installなし。重い処理前の容量gateは1.01〜1.21GiBで通過した。

| 検証 | 実結果 / 証拠（所有treeのwork） |
| --- | --- |
| 新book単独 | 1 scenario / 0 skipped / 0 failures。`work/baseline-runn-first.log` |
| 通常API glob | 新book含む10 scenarios / 0 skipped / 0 failures。`work/baseline-runn-all.log` |
| 必須baseline | tenant各52請求、50+2 keyset、正規previewで未登録・confirmで52件、相互tenantの一覧空/詳細404、原行保持。未絞込既定先頭50件も全て2020年以前かつ次pageありをassert |
| 原本保持 | 初回2顧客 /104 active請求 /104原revision /106監査が全API後・再起動後・E2E後とも同一。`work/baseline-retention.log` |
| 同artifact | 新treeで生成したjar SHA256 `6011c82a521b1e5c8e7f324b8534f0f058590fcf1a6373c9fe6bd76762b96d13`、再起動/E2E前後一致。`work/backend-artifact.sha256` |
| Browser | 同じDBで現main全14件を2回成功。`work/baseline-e2e.log`、`work/baseline-e2e-second.log`。修正したharness自身もexit0、原本不変、8096/5278両port解放。`work/baseline-browser-acceptance.log` |
| Python | 38件成功。UUID独立性/tenant毎52 CSV行/古日付/金額、およびcanonical verifyの通常API globが実bookを含めて起動することを検査。`work/baseline-python.log`、`work/baseline-phase-tests.log` |

原本fingerprintは`8dc42c1124c4cf22d535ecc17e0c59446bc492e678398ce6889ff6a01e76466d`。最初の104請求を残して全APIを実行したため、browser開始時には同tenant各104件の古い請求が存在する。原本snapshotは顧客・請求・revision・対応auditだけをread-only SQLで取得し、fixtureの作成は全て正規APIで行った。

最初の相当harnessは別shellへ引き継いだ旧PIDがzombieになり`kill -0`だけで停止判定を誤った。graceful shutdownの実logとport解放を確認して再起動した。次のBash trapもcleanupで内部errorとなりVite子processが残ったため、実pathで所有子を特定して停止し、同親`Popen.wait`と所有process group限定cleanupへ修正した。canonical verifyは同親waitを実装済みなので変更していない。検証harnessの失敗を業務APIやCIの成功へ置き換えない。

相当harnessもprocessの親を維持して正常終了値をwaitで受け取り、npm wrapperだけでなく所有process groupを停止する。既存verifyのlifecycleと終了値保持を参照し、各phaseのport解放・artifact一致・DB原本保持を検査する。別shellからの`kill -0`だけを終了判定やcleanupの成功根拠にしない。修正版harnessは同DBの全14件成功後、所有2process group停止・両port空きを確認してexit0。初回原本fingerprintは2回目E2E後も一致した。

Collection UIはこのmainに存在せず本変更の対象外。GitHub CI・独立review・全BE suite・本番proxy再実行は未実施。
