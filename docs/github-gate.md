# GitHub merge gate 設定案

Issue #1 / #7の実証待ち。metadataのadmin=trueと設定APIの実行権限は異なり、この連携はbranch protection readで403を返す。自動設定の試行は実行されていない。

mainの提案設定（GitHub Settings → Branches / Rules）:

- Require a pull request before merging。
- Require status checks to pass: `quality` と `independent-review`。
- Require branches to be up to date。
- 管理者を含めて適用し、force push / deletionを禁止。
- 会話の解決を要求。

`independent-review`は保護default branchのGitHub Actionsが発行するrequired commit status。pull_request_target / issue_commentを入口とし、PRのコード・判定器・workflowは実行しない。PR reviewの明示レポートを読み、review.commit_idとレポートのhead_shaが最新PR headと一致すること、trusted association、最新判定pass・blocking_findings=0を検証する。commit status書込みAPIは403であるため反復せず、Actions tokenのstatuses:writeで発行する。COMMENT reviewを投稿後、PR Conversationへ再検査コメントを追加すると最新SHAを照合する。Reviewerへ実装会話を継承せず、Issue/仕様/ADR/SHAを渡す。blocking findingsを解消し、同じSHAでreview完了したときだけsuccessにする。レビュー後変更ではstatus未設定の新SHAになるため通過不可。無コメント・古いSHA・外部者のレポート・blocking判定を拒否する単体テストを含む。

Reviewerは対象SHA・検査範囲・実行した検証・findings/未検証事項とともに、COMMENT reviewへ次のmarkerを含める。APPROVEの自己承認制約を回避して承認を偽装するものではなく、独立Cloudレビューの証跡を機械照合する方式である。

```text
<!-- independent-review: {"schema":1,"head_sha":"<対象40桁SHA>","outcome":"pass","blocking_findings":0} -->
```

コンテキストが独立していたこと自体はGitHub APIから証明できないため、運用上の境界とReviewerレポートも保存する。設定読取/変更/status書込みの403は、接続権限が変更された明示的な証拠が届くまで再試行しない。metadata.admin=trueだけでは再試行の根拠にしない。

単独開発ではPR author本人がGitHubのAPPROVEを提出できない。GitHub標準の承認人数に代え、別contextのreview証跡と上記required statusを使用する案。これはGitHubによる別アカウントの人間承認とは異なる。既存review設定がある場合は無断で削除せず、ユーザーの具体的な判断を要する。

設定完了後も、(1)故意CI失敗、(2)CI成功でもreview pending/failure、の各probe PRでGitHub merge APIの拒否を確認する。確認前にauto_mergeを有効化しない。probeは架空の検証用変更だけで作成し、拒否が証明できないときにはmerge APIで試さない。

Actionsはpublic repositoryの標準 `ubuntu-24.04` runnerのみ。workflowは最小contents:readで動作し、有料APIやlarger runnerを使わない。

## 小さなbootstrap PR

設計＋CI、OpenAPI契約、BE、FE、自己改善、実行基盤に分け、前段branchをbaseとする。まだ存在しないアプリのテストを成功したと報告せず、各段階で存在するコードを検証する。最終実行基盤PRでは全lint/type/unit/API/実PG/E2Eを必須とし、基盤全体の受入条件はその成功と独立レビュー・gate実証が揃うまで未完了。生成lock/types/wrapperはgenerated属性で表示を畳めるようにする。

## 初期trust seedの阻害

新規repositoryのmainにはまだこのworkflowがない。pull_request_target/issue_commentはdefault branch上のworkflowで実行されるため、初回PRの未mergeコードだけではtrusted gateを導入できない。PR headを実行するfallback、無条件success、古いレビュー流用は実装しない。初期workflowを保護された信頼元へ配置する方法を確立するまでbootstrap mergeは保留し、必要な権限/導入手順をIssue #7へ記録する。
