# Repository work rules

## 目的と実行境界

- 業務要件の入口は [Issue #1](https://github.com/takattty/debt-management/issues/1)。仕様の一覧は `docs/requirements.md`、業務例は `docs/business-examples.md`。
- 設計・実装・独立レビューは Cloud で進め、開発者の Mac の常時起動に依存しない。成果物はローカルの Docker で起動可能にする。
- 日本語で簡潔かつ丁寧に報告する。識別子、クラス、メソッド、フィールド、ログ、設計上の概念名は英語を維持する。
- 架空データだけを使用する。追加 API 課金、未承認の有料サービス、商用デプロイを開始しない。
- 未決の業務判断はユーザーに質問し、その回答に依存しない作業を継続する。未決事項を暗黙の仕様にしない。

## モデルと変更

- FE は React / TypeScript、BE は Java 25 / Spring、DB は PostgreSQL。FE と BE を一つの repository で管理する。
- `docs/design.md` と ADR の境界は業務上の責任境界であり、サービスや DB の分割を要求しない。
- 業務ロジックはユースケースと不変条件から設計する。CSV/API/DB の都合だけでドメインを決めない。
- 金額は日本円の整数で扱い、浮動小数点を使わない。業務日付と記録時刻を分ける。
- Tenant は認証済みの利用者から決定する。クライアント指定の Tenant ID を信頼しない。すべての読み書きと関連付けで tenant 分離を検証する。
- 認証・招待が完成していない開発用縦断実装は、開発専用であることと制限を明示する。機能完成と報告しない。
- 確定済みの履歴を破壊的に書き換えない。取消、楽観ロック、操作履歴、重複防止は後付けにせず各ユースケースの受入条件に含める。
- 並行編集時は担当者とファイル範囲を決める。共有ファイルの無断編集や他担当者の変更の巻戻しをしない。

## 開発ループ

作業開始時と関連操作の直前に `docs/learning/failures.json` と [Failure learning loop](docs/learning/README.md) を参照する。同じ原因の別失敗を 2 回観測したら、原因・予防策・実際の検証結果・次回 guard を保存し、`python3 scripts/failure-loop.py check` が通るまで同じ失敗操作を繰り返さない。同じ証拠の再読は新しい失敗に数えず、解決後の再発は再度改善対象とする。依存しない作業は継続する。

1. Issue に範囲・依存関係・受入条件・検証コマンドを定義する。
2. 実装し、対象 commit を固定して Lint、型、単体、API、実 PostgreSQL 統合、画面→DB の E2E を実施する。
3. 実装会話を継承しない別コンテキストの Reviewer に Issue、仕様、ADR、対象 commit と変更範囲を渡す。
4. Reviewer は所見だけでなく、検査範囲、実行した検証、対象 SHA、blocking finding の有無を明示する。無コメントは合格を意味しない。
5. 修正後は必要な再検証・再レビューを行い、最新 SHA に証拠をそろえる。
6. GitHub の強制可能な gate の実効性を故意の失敗例で確認し、Issue の条件が満たされたときだけ merge する。

Public/Freeと標準Linux Actions利用はユーザー確認済み。2026-10-29にallow_auto_merge=true、active main ruleset・bypassなし・必須quality/independent-review・strict最新baseを実API確認し、失敗時拒否も実証した。チェックを無効化せず維持する。拒否済み設定/billing/status APIは権限変更の証拠なく再試行しない。新しい有料サービス/larger runnerは導入しない。

GitHub CLIは `scripts/gh-repo.sh owner/repository command ...` でrepositoryを必須指定する。証拠のworkファイルは `python3 scripts/read-evidence.py work/name.log` で所有worktreeを解決し、同名が複数なら `--worktree` を明示する。複数treeを扱うCoordinatorはhelper自身もactive code checkoutの絶対pathで呼び、古い証拠ownerのcheckoutに新helperがあると仮定しない。別treeの操作/読込を一つのcwd依存commandへ混在させない。

## PR と API 契約

- PRは目的を一つに絞り、設計・契約・BE・FE・実行基盤を依存関係付きの小さなPRへ分ける。生成物は明示し、巨大な差分へ手書きの変更を埋めない。
- FE/BEの契約源は `contracts/openapi.yaml`。型とAPI interfaceを生成し、生成物の手編集や別々のtransport型の定義を避ける。契約変更と生成整合をCIで検証する。
- BEはGradle Kotlin DSL/Java25。Mavenへ戻さない。
- HTTP APIの受入テストはrunnで記述し、OpenAPIレスポンス検証を維持する。不正リクエストのケースだけrequest検証を外してserverの拒否を確認する。JUnitはdomain/DB/transaction/競合の統合検証として併用する。

## 再開と証拠

- 必要時は repository 内の `.agents/skills/delivery-loop/SKILL.md` を読む。個人のローカル Skill が Cloud にあると仮定しない。
- Coordinator の状態は Issue、依存関係、ブランチ、対象 SHA、CI、独立レビューから復元する。
- Issue に「実施済み」「検証済み」「未実施」「阻害要因」を分けて記録し、コマンド、結果、証拠リンク、次の操作を残す。
- サブエージェント、別タスク委譲、停止後再起動、`/goal`、常時稼働 Coordinator を同一視しない。使っていないモデルや未対応機能を検証済みと報告しない。
- 実行上限や権限不足で停止するときは、未コミット変更を含む保存場所、再開コマンド、依存する回答を Issue に記録する。
