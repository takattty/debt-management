# PR metadata validation

2026-10-30、Cloud、Python 3 stdlib。対象は `scripts/gh-repo.sh` と `scripts/gh-pr-metadata.py`。起点mainは `6240f1d50fd7fdf364cfd07b22b94989cde01618`。

## 再発と原因

PR38再開時、Coordinatorが `gh pr view 38 --json number,url,state,headRefOid,baseRefOid,statusCheckRollup,reviews` を実行し、CLIが `baseRefOid` を未対応fieldとして終了値1で拒否した。既存の `github-metadata-shape` は2eventsで解決済みだったが、文書の注意だけでは同じ誤用を防げなかった。再発eventは `cli-baserefoid-recurrence-20261030-pr38-resume` として1件だけ記録し、失敗数を増やすためのAPI再試行は行っていない。

予防はhead/base取得を専用REST helperへ固定し、既知のCLI誤用をwrapperが通信前に拒否すること。応答の欠落や形の変更を成功扱いせず、PRの識別情報・状態・両SHA/refが揃ったときだけ固定JSONを返す。

## 検証

`python3 -m unittest discover -s tests -p 'test_gh*.py' -v` は8 tests成功。fake `gh` をcheckout外cwdで実行し、実secret/APIを必要としない検証を行った。

| 検査 | 結果 |
| --- | --- |
| 明示repository、誤った継承GH_REPO/GH_HOST、checkout外cwd | 指定repository・github.com・GET・REST pulls endpointで呼び出す |
| 正常REST応答 | head.sha/base.shaから固定fieldだけを出力し、本文等を出力しない |
| JSON不正、PR番号/URL/状態不整合、SHA/ref欠落・不正 | 終了値1、stdout空、raw応答を表示しない |
| APIエラー | 終了値7とstderrを保ち、成功らしいmetadataを出力しない |
| repository省略・不正、PR番号不正 | 終了値2、gh未起動 |
| 未対応baseRefOid選択の空白区切り・等号形式 | 終了値2、gh未起動、専用helperへ案内 |
| 対応済みPR fieldと他の既存command | 引数を変えずghへ渡す |

実APIは `/tmp` をcwdにして `python3 /workspace/debt-github-metadata-guard/scripts/gh-pr-metadata.py takattty/debt-management 38` を実行し、終了値0を確認した。以下は [PR #38](https://github.com/takattty/debt-management/pull/38) の取得時点の値であり、最新mainのSHAではない。

| field | 取得値 |
| --- | --- |
| number / state / merged | 38 / closed / true |
| headRefName | fix/csv-proxy-capacity |
| headRefOid | 2050adaf462c93093a8ef4f9159e6fa83a62130f |
| baseRefName | main |
| baseRefOid | 9e46a14a705740122e58f9f87e00bbcf1d553d3c |

レビュー・チェック・status creator取得の契約は今回のhelperに含めず、既存の対応endpointを使う。業務機能、Java、PostgreSQLの変更は行っていない。

`python3 -m unittest discover -s tests -v` は全18 tests成功、`bash -n scripts/gh-repo.sh` と `git diff --check` も成功。再発を記録した直後のfailure-loop checkは終了値1でblockし、対策と上記結果をresolveへ保存した後のcheckは終了値0となった。
