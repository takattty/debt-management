# V8 内部台帳を持つ HTTP 受入

公開 allocation/reversal command が未導入のため、この book は通常の wildcard run から分離する。
repository root で `python3 backend/runn/ledger/prepare-fixtures.py` を実行すると、
一意ID/fingerprintの `work/ledger-runn-seed.sql` とIDだけの `work/ledger-runn.env` を生成する。
SQLを専用の local 架空DBへPG clientで明示適用し、`set -a; source work/ledger-runn.env; set +a` で以下のIDを渡す。
このscriptはDBへ接続せず、既存DBの内容を削除しない。
既存 DB の削除・初期化や、本番用の fixture/command endpoint は追加しない。

- `RUNN_LEDGER_CUSTOMER`: Alpha の4請求を持つ一意顧客。
- `RUNN_LEDGER_PAYMENT`: 原本120000円、振込日9999-12-31、部分請求へcash60000の入金。
- `RUNN_LEDGER_PARTIAL`: 原本100000円、active cash60000 + explicit fee440、version1。
- `RUNN_LEDGER_FEE_ONLY`: 原本100000円、別のactive操作でcash0 + explicit fee1、version1。
- `RUNN_LEDGER_CANCELLED`: 原本100000円、消込なしの取消済み請求。
- `RUNN_LEDGER_REVERSED`: 原本100000円、cash1 + fee1の操作を全取消済み、version2。

各原請求にCREATED revisionを保持する。操作/line/state/version は LedgerRepository.lockPositions と
同じ順序で、READ COMMITTED とV8制約を満たす内部fixtureとして保存する。
原入金・原操作・line・原請求revisionはbook前後のfingerprintを比較する。
bookの成功操作はREVERSED請求の訂正1件だけで、version3・revision・監査が追記される。loginのSESSION_STARTED監査も別に1件追記される。
毎回一意なfixtureを用い、既存の別請求や別入金を消さない。payment探索は全keysetを走査する。

```sh
RUNN_BASE_URL=http://127.0.0.1:8113 scripts/runn.sh run --scopes read:parent backend/runn/ledger/read-guard.yml
```

HTTP側は正本OpenAPIのrequest/response validationを維持する。
DBの実ロック競合・監査故障・高isolation拒否はJUnitの実PG suiteで扱う。

このsubdirectoryは `backend/runn/*.yml` の通常CI globには含まれず、現段階では明示実行の独立probeである。
CI完了の根拠は追加bookの存在ではなく、通常のBE checkで動く実PG/HTTP JUnitのread/guard回帰。
B06/B07回答後に正規commandsを実装した際はAPI作成→確定/取消→公開残高の正規runn/E2Eへ置換し、CI必須対象へ統合する。
