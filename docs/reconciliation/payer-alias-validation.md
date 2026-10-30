# Manual payer alias runtime validation

準備実装。契約baseは`cea76912ad9e80fe26024255f10d0a256fbdaba0`、その親はstage2 `ffcb76e606cef62cd4e61e03413c8ecdbbff9de8`。旧実装`d5f4b73ee7051df3c0ed13b76b9e8e60c56e17ea`のAPI/SQL/bookを保持し、HTTP Unicode境界の回帰1件を追加した。旧独立レビューのPASSを今回SHAへ流用しない。

| 検証 | 今回の結果 / 所有treeのwork証拠 |
| --- | --- |
| BE/実PostgreSQL | openApiValidate/check/bootJar成功、172件、0 failures/errors/skipped。競合・audit rollback・tenant FK・不変履歴・JSON型を含む。`work/alias-runtime-be.log` |
| Unicode/型 | HTTPでstrip後200emoji名義と500emoji理由を受理し、201/501・非Stringと不正versionを拒否。生成長さ制約は既存CodePointSize、CSV名義ruleと同じ。`work/alias-runtime-seed-acceptance.log`、契約treeの`work/alias-generated-length-proof.log` |
| HTTP/OpenAPI | 各tenant105active aliasと無効履歴1行を正規APIで保持し、alias単独と通常12冊を同jar/同DBで反復。原business/audit全行hash保持。`work/alias-runtime-one-acceptance.log`、`work/alias-runtime-repeat-acceptance.log` |
| Session/運用 | book全31requestの明示Cookie、Python63件、failure-loop/diff check成功。`work/alias-cookie-policy-proof.json`、`work/alias-runtime-python.log` |
| 保持 | frozen API/SQL/bookと、最新baseのpayment/strict date/customer race/古い請求52件/VFS6events/全learningを保持。`work/alias-runtime-source-proof.json` |

無効化は原行を保持する一方向遷移で、再登録は新ID。同名複数顧客を許可し、候補や残高は変えない。B06/B07は後続ledgerで扱う。FE画面・候補・消込、今回のbrowser E2E/proxy、GitHub CI、同SHAの独立レビューは本記録に含めない。
