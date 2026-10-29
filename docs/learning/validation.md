# Failure loop validation

2026-10-29、Cloud、Python 3 stdlib。使い捨て `TemporaryDirectory` を保存先にし、CLI を別 process で呼び出して終了値と保存内容を検査した。通常の失敗記録へ架空 event を登録していない。

| 検査 | 結果 |
| --- | --- |
| 保存先が存在しない `check` | 終了値 2。未記録を合格としない |
| 同じ原因の初回 event | `check` 終了値 0 |
| 同じ event を再登録 | event 数は 1 のまま |
| 同じ原因の別 event を登録 | event 数 2、`check` 終了値 1 |
| 存在しない guard で resolve | 終了値 2、既存記録は不変 |
| 原因・対策・検証・実在 guard を保存 | `check` 終了値 0 |
| 解決後に既存 event を再読・再登録 | 解決状態を維持 |
| 解決後に同じ原因の新 event | status が open、`check` 終了値 1、以前の対策も保持 |
| status 文字列だけ resolved に変更 | event 数と resolution から導出し直し、終了値 1 |
| query token 付き証拠 URL、repository 外の guard | 受け付けず終了値 2 |
| 壊れた JSON | 終了値 2 |

通常の `python3 scripts/failure-loop.py check` と `git diff --check` も成功した。これは CLI の状態遷移と入力制約の検証であり、実際の GitHub 権限不足が解消した証拠ではない。
