# 消込履歴readの検証

baseは独立レビュー済command修正 `9c045adb5a9b135beb8b0b4045e71f7c8506c662`、
契約は `e5df1d1f3eda75b3a2091a42a5a2ff3b71ae71fd`。
本readのrepository/Controller/PG/HTTP受入は同一差分で検証した。

| 検証 | 結果 | `debt-allocation-history` 所有の証拠 |
| --- | --- | --- |
| Java/Test compile、OpenAPI/checkstyle | 成功。初回compileのfixture `header` 名衝突を修正し、FAIL/PASS別logで保持 | `work/history-compile-01.log` / `work/history-compile-02.log` |
| FE生成整合/型 | 成功 | `work/history-frontend-check-01.log` |
| 実PG対象回帰＋bootJar | history5＋command8＋transport2、15test/0fail/0skip | `work/history-targeted-check-01.log` |
| 通常runn全glob | 13book/0fail/0skip、allocation内に履歴3GETを追加 | `work/history-runn-all-01.log` |
| 実SQL readonly probe | 同一SQLで3operation×2line、BEGIN READ ONLY→ROLLBACK | `work/history-query-readonly-01.log` |
| HTTP process終了 | 同親の正常SIGTERM/wait、runnExit0/backendExit143 | `work/history-http-result-01.json` |
| 集計/JAR SHA256 | 保存済 | `work/history-validation-summary-01.json` |

実PGは専用架空DB `debt_allocation_history`。空履歴/GET書込ゼロ/tenant404/認証、
顧客横断cash/feeと原操作取消link、actor email/UUID/理由/時刻、請求訂正後のcurrent版、
全lineを保つkeyset、2^53超のexact invoice版、最大JPY、並行確定/取消中の
operation/line/current版/reversal状態が一snapshotであることを検証した。
新readは一SQLでページ・line・actor・現在版を読み、N+1やGET初期化を行わない。
各logはunique名とexclusive creationで既存のFAIL/PASSを置換しない。

独立review、統合後の全BE/画面E2E/CI、V9 upgradeはこのreadの検証記録では未実施。
