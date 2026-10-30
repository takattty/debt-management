# Payment preview runtime validation

入金preview実装は契約branch `63619239dd7abe343280047802d2fceb9e9ce231`に依存する。元実装`61ae48d2550e41a8a0ced7df3857c11c12a107b1`、全機能凍結`56c16df2ac2c3f69b261ce0144e0fb8af6433422`を保持し、今回のruntimeは専用`PaymentImportsApi`を既存controllerへ追加実装する。業務ロジック・SQL・テスト・正規runnは凍結版と同一。

## 検証境界

| 検証 | 同treeの証拠 |
| --- | --- |
| BE/実PostgreSQL | 全153件、0 failures/errors/skipped、openApiValidate/check/bootJar。`work/runtime-be.log`とJUnit XML |
| HTTP/OpenAPI | payment単独と通常11冊。旧請求52件/tenantを残し、同jar・同DBで原businessと原audit全行hashを保持、payment0。`work/runtime-http-acceptance.log`、`work/runtime-runn-all.log` |
| preview audit | 単独bookの追加auditは正規loginのSESSION_STARTED2件のみ。専用JUnitはlogin後をaudit基準にする |
| source/学習 | 全base event/resolution保持、新3keyだけをunion。`work/runtime-source-proof.json`、failure-loop/diff check |

追加FE画面、proxy、browser E2E、GitHub CI、最新SHA独立レビューは本記録の検証に含めない。通常CIの既存API/E2E/proxy gateを維持する。

## 保存した失敗と実行guard

旧portは専用DB作成前にGradleを開始しdatabase不存在で失敗した。容量検査→専用DB作成と実SELECT→新Gradle JVMの順を必須とする。最終成功と初回失敗を混同しない。旧証拠は`/workspace/debt-payment-preview-main/work`、旧readyの153件/HTTP11冊は`/workspace/debt-payment-preview-ready/work`に保持した。

旧補助harnessはlogin前audit全不変を仮定して正規SESSION_STARTED2件を誤認した。businessと原audit全行保持、追加auditは正規loginだけという境界へ修正した。旧顧客CSV分類raceはmainのrollback後確定元照会と同期回帰で修正済みであり、新eventを重複計上しない。今回も修正sourceを保持し、全BEで検証する。
