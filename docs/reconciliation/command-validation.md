# 公開消込commandの検証

対象契約: `d191446a455ca13f670f09da52970d701bbf2855`。
対象実装: `bd94e8313a22bb3ae110627a4685e2ce21df3ccd`。
base: `d54aa4eb268e96655ddfe2b08418bbc76eb7def3`。

Cloudの専用架空DB `debt_settlement_commands` とJava25/Gradle9.1 cacheを使用した。
Docker build/upや既存DBの削除は実施していない。正常終了後は同親processでbackendをSIGTERM/waitした。

| 検証 | 結果 | 所有worktreeの証拠 |
| --- | --- | --- |
| BE `./gradlew --offline --no-daemon check bootJar` | 39suite / 206test、0fail、0skip。新command8testと既存ledger/read/guard/candidates含む | `work/command-check.log` / `backend/build/test-results/test` |
| OpenAPI lint/生成整合、FE typecheck | 成功 | `work/command-api-check.log` / `work/command-fe-type.log` |
| Python `unittest discover -s tests` | 12test成功 | `work/command-python-tests.log` |
| 通常API glob `scripts/runn.sh run --scopes read:parent backend/runn/*.yml` | 13book、0fail、0skip。同DBで2回成功、最新実行logを保存 | `work/command-runn-all.log` |
| 実HTTP backendと終了値 | 同一JAR、port8126。runnExit0、backendExit143（SIGTERM） | `work/command-http-backend.log` / `work/command-http-result.json` |
| 実DB照合 | CONFIRM4/REVERSE2/fingerprint6/audit6、二回分の原入金は各120000円のまま | `work/command-db-evidence.txt` |
| 検証集計とJAR SHA256 | 206test成功、artifact SHAを保存 | `work/command-validation-summary.json` |

新testは同tenant複数顧客、現金/明示手数料、過入金、独立fee-only拒否、同操作内fee-only行、
原操作再送、取消後の元応答再送、異payload/別payment/別kindのrequestId再利用、
全体取消/二重取消、元集合不足、古い版、取消済請求、他tenant、
監査故障の全体rollback、最大金額/版overflow、同request並行再送、
異なる入金から同請求への並行充当を確認する。

生成物はTypeScript schemaだけをcommitし、Java DTO/interfaceはbuildで正本OpenAPIから生成する。
追加の`allocations.yml`は通常globに入り、正規CSV/APIでfixtureを作る。
独立review・GitHub CI・画面E2E・公開操作履歴read・V8既存DBへのupgrade実証は未実施。
最新mainの既存請求baseline等はこのprepのbaseに含まれないため統合後の通常CIで維持する。
未mergeのCollection V9との統合順を決め、CollectionをV10へ改番しupgrade/checksum証拠をそろえる。

証拠readerはactive checkoutの`/workspace/debt-issue3-local-acceptance/scripts/read-evidence.py`を
絶対pathで実行し、artifactは`work/name.log`、ownerは`--worktree /workspace/debt-settlement-commands`とする。
