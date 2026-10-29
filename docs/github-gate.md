# GitHub merge gate 設定案

Issue #1 / #7の実証待ち。metadataのadmin=trueと設定APIの実行権限は異なり、この連携はbranch protection readで403を返す。自動設定の試行は実行されていない。

mainの提案設定（GitHub Settings → Branches / Rules）:

- Require a pull request before merging。
- Require status checks to pass: `quality` と `independent-review`。
- Require branches to be up to date。
- 管理者を含めて適用し、force push / deletionを禁止。
- 会話の解決を要求。

`independent-review`は独立したCloud contextの明示結果を対象SHAに記録するcommit status。Reviewerへ実装会話を継承せず、Issue/仕様/ADR/SHAを渡す。blocking findingsを解消し、同じSHAでreview完了したときだけsuccessにする。レビュー後変更ではstatus未設定の新SHAになるため通過不可。

単独開発ではPR author本人がGitHubのAPPROVEを提出できない。GitHub標準の承認人数に代え、別contextのreview証跡と上記required statusを使用する案。これはGitHubによる別アカウントの人間承認とは異なる。既存review設定がある場合は無断で削除せず、ユーザーの具体的な判断を要する。

設定完了後も、(1)故意CI失敗、(2)CI成功でもreview pending/failure、の各probe PRでGitHub merge APIの拒否を確認する。確認前にauto_mergeを有効化しない。probeは架空の検証用変更だけで作成し、拒否が証明できないときにはmerge APIで試さない。

Actionsはpublic repositoryの標準 `ubuntu-24.04` runnerのみ。workflowは最小contents:readで動作し、有料APIやlarger runnerを使わない。
