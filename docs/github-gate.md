# GitHub merge gate

Issue #1 / #7に実証結果を記録する。Public/Free、auto-merge有効、main rulesetはactive・bypassなしを実APIで確認済み。metadataのadmin=trueと設定APIの実行権限は異なり、classic branch protection readの403を繰り返さずruleset APIで確認する。

mainの確認済み設定（ruleset26124161）:

- Require a pull request before merging。
- Require status checks to pass: `quality` と `independent-review`。発行元はGitHub Actionsに固定。
- Require branches to be up to date。
- 管理者を含めて適用し、force push / deletionを禁止。
- GitHub標準の必須承認人数は0、独立Cloudレビューの明示結果を下記statusで検証する。

`independent-review`は保護default branchのGitHub Actionsが発行するrequired commit status。pull_request_target / read-only review relayのworkflow_run / issue_commentを入口とし、PRのコード・判定器・workflowは実行しない。PR reviewの明示レポートを読み、review.commit_idとレポートのhead_shaが最新PR headと一致すること、trusted association、最新判定pass・blocking_findings=0を検証する。commit status書込みAPIは403であるため反復せず、Actions tokenのstatuses:writeで発行する。reviewのsubmitted/edited/dismissedでread-only relayが起動し、default branchのconsumerが現在情報を再取得する。trusted consumer自身がhead relayのblobとprotected defaultのblob一致を確認する。status書込みは全イベント共通groupで直列化し、各runは全open PRを再評価する。relayの成果物や結論は信用しない。Reviewerへ実装会話を継承せず、Issue/仕様/ADR/SHAを渡す。blocking findingsを解消し、同じSHAでreview完了したときだけsuccessにする。レビュー後変更ではstatus未設定の新SHAになるため通過不可。無コメント・古いSHA・外部者のレポート・blocking判定を拒否する単体テストを含む。

Reviewerは対象SHA・検査範囲・実行した検証・findings/未検証事項とともに、COMMENT reviewへ次のmarkerを含める。APPROVEの自己承認制約を回避して承認を偽装するものではなく、独立Cloudレビューの証跡を機械照合する方式である。

```text
<!-- independent-review: {"schema":1,"head_sha":"<対象40桁SHA>","outcome":"pass","blocking_findings":0} -->
```

コンテキストが独立していたこと自体はGitHub APIから証明できないため、運用上の境界とReviewerレポートも保存する。設定読取/変更/status書込みの403は、接続権限が変更された明示的な証拠が届くまで再試行しない。metadata.admin=trueだけでは再試行の根拠にしない。

単独開発ではPR author本人がGitHubのAPPROVEを提出できない。GitHub標準の承認人数に代え、別contextのreview証跡と上記required statusを使用する案。これはGitHubによる別アカウントの人間承認とは異なる。既存review設定がある場合は無断で削除せず、ユーザーの具体的な判断を要する。

設定完了後も、(1)故意CI失敗、(2)CI成功でもreview pending/failure、の各probe PRでGitHub merge APIの拒否を確認する。確認前にauto_mergeを有効化しない。probeは架空の検証用変更だけで作成し、拒否が証明できないときにはmerge APIで試さない。

Actionsはpublic repositoryの標準 `ubuntu-24.04` runnerのみ。workflowは最小contents:readで動作し、有料APIやlarger runnerを使わない。

## 小さなbootstrap PR

### 現行の本文検査と同SHAの扱い

全open PRのうち、保護されたdefault branch（main）をbaseとするPRが判定対象。本文は[PRとコメントの書き方](pr-writing.md)の必須7節を検査し、`edited`イベントでも再評価する。
commit statusはSHAに結び付くため、同じhead SHAを持つmain向けPRを集約し、全PRの本文・独立レビュー・relayが合格した場合だけsuccessにする。別baseのPRはstatusを書き込まない。
判定中に本文・head・対象PRの集合が変化した場合はpendingを維持し、後続イベントで再評価する。`closed`も再評価対象。merge直前には最新本文とレビューを読み直し、非同期イベントの完了を待つ。
これらの判定はprotected mainのコードで行い、書込権限でPRのコードを実行しない。実GitHub上の本文編集による失効・修復の実証はIssue #1へ保存する。

設計＋CI、OpenAPI契約、BE、FE、自己改善、実行基盤に分け、前段branchをbaseとする。まだ存在しないアプリのテストを成功したと報告せず、各段階で存在するコードを検証する。最終実行基盤PRでは全lint/type/unit/API/実PG/E2Eを必須とし、基盤全体の受入条件はその成功と独立レビュー・gate実証が揃うまで未完了。生成lock/types/wrapperはgenerated属性で表示を畳めるようにする。

## 初期trust seedの阻害

新規repositoryのmainにはまだこのworkflowがない。pull_request_target/issue_commentはdefault branch上のworkflowで実行されるため、初回PRの未mergeコードだけではtrusted gateを導入できない。PR headを実行するfallback、無条件success、古いレビュー流用は実装しない。初期導入の唯一の例外案を `bootstrap-review.yml` に分離した。独立レビュー済みの固定SHAをユーザーがRepository Actions variable `BOOTSTRAP_REVIEW_SHA`へ設定し、そのSHAの一回限りの信頼付与を明示承認した場合だけ実行できる。初期base SHAも固定し、コード変更でpin不一致、初回mergeでbase不一致になる。未設定では実行せず、無条件successもrequired checksの削除もしない。承認前はbootstrap merge保留。

レビュー変更からActions実行までは待ち時間があり、GitHub標準承認と同じ原子性は保証しない。Coordinatorもmerge直前に最新head/reviewsを再取得しblockingを確認し、merge APIへ期待SHAを渡す。初期導入後はBOOTSTRAP_REVIEW_SHAを削除し、bootstrap workflowを後続のreview付きPRで除去する。

## 初期導入の終了

2026-10-29、ユーザーが独立レビュー済み固定SHA e7b6d06f439bdd5ec664a935a67445e2f91139a8 の初回実行を承認。bootstrap run40884695535が実statusを発行し、quality/independent-review成功とhead/base/最新reviewを確認してPR #9をmergeした。mainは737c01e40de013ab7d2fea8b567e52187fff9085へ進み、初期base条件は失効済み。本変更でbootstrap workflowを除去する。変数もユーザーが削除したと報告済み。以降このrepo内で初期経路を再導入しない。通常のreview relay/consumerとrequired checksを維持する。

## 最新レビューの撤回

同SHAの最新trusted review自体を判定する。最新レビューがmarkerなし、dismissed、pending、不正または複数markerなら失効し、過去のpassへ戻らない。無関係な補足はConversation commentで行い、reviewを追加した場合は最新reviewへ明示結果を記載する。

marker削除とdismissで古いpassへ戻る不具合を2ケースで再現した。回帰テストは旧判定器で失敗し、修正版の3テストは成功。これはローカル判定器の検証であり、導入後のGitHub上の撤回→status failure→merge拒否はIssue #7へ別途記録する。

追加実証は[Issue #7](https://github.com/takattty/debt-management/issues/7#issuecomment-6503430700)に記録済み。PR #26はquality/旧passを残したまま最新markerを削除し、review editedイベントからrequired status failure、実merge HTTP405、main不変を確認して未mergeで閉じた。dismissed/pending/複数markerは単体で、GitHub実イベントはmarker削除の範囲を実証した。
