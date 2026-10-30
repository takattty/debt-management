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

大きいJSON/logはwork artifactへ保存し、tool stdoutには小さいmetadataか `read-evidence.py --json-chunk --offset N --size 4096` だけを出す。通常表示はheader込みUTF-8 16KiB上限、超過はpayloadなしで拒否する。chunkは最大4096byteのbase64とtotal/whole SHA256をJSONだけで返す。各chunkのowner/path・offset・size・total・SHAを確認し、再結合後もwhole SHAを照合する。raw exec/tool結果をJSON parseする前に完了・exit0・出力上限とtruncationなしを確認する。`Warning: truncated`等を削って壊れたJSONをparseし直さない。詳細は[Evidence output validation](docs/learning/evidence-output-validation.md)。

PRのhead/base取得は `python3 /absolute/active-checkout/scripts/gh-pr-metadata.py owner/repository PR_NUMBER` を使う。REST応答を検査し、`headRefOid` / `baseRefOid` を返す。`gh pr view --json baseRefOid` は未対応のためwrapperで拒否する。

過去文脈・summaryに推定名があっても、ソース読込は同checkoutのcatalogを先の別callで観測し、その実出力から次callのpathを作る。`python3 /absolute/active-checkout/scripts/read-source.py Invoice --list --worktree /absolute/target-checkout` でGit管理下の実在sourceを列挙し、次callで観測した相対pathと `--start 1 --end 180` を指定して読む。複数候補・不一致時は内容を読まず候補を返すため、推測pathで直接cat/sedを反復しない。

Cloudのログ保存付き検証は `bash /absolute/checkout/scripts/verify-cloud.sh` を外側リダイレクトなしで実行する。新worktreeのworkディレクトリ作成とログ保存はwrapperに任せ、終了値とwork/cloud-verify.logを確認する。

新worktree・複数worker開始前は `df -h /workspace` と `python3 /absolute/active-checkout/scripts/check-workspace-capacity.py /workspace` で空き容量を確認する。重いbuild/npm/E2Eは1GiB未満または測定不能なら停止する。cleanupは所有者が完了を確認したtreeの再取得可能な `frontend/node_modules` / `work/npm-cache` だけを、`git check-ignore` とsymlinkでないことを確認して対象別に行う。稼働中tree・source・jar・DB・証拠/logを保持し、worktreeやnode_modulesを無差別に削除しない。verify/CIの容量gateをskipしない。read helperやgit読取を容量gateで止めない。

API/runn/proxyとbrowser E2Eは同じruntimeで並行実行しない。verifyはAPI phase後に同一artifact・同一DBのbackendを正常停止/再起動し、process内login counterをphase間で分離する。limiterの閾値・window・CSRFを変えず、固定sleepや429再送で検証を通さない。phaseごとのbackend logと失敗終了値を保持する。

## PR と API 契約

repository内scriptの実行にもcatalog確認を適用する。新しいscript名は責務や過去文脈から作らず、先のcallで `rg --files scripts` の実出力を観測し、次callで観測した実pathだけを実行する。読込helperだけを使っても、未確認名を `python3` / `bash` へ渡せば同じ誤りになる。

- PR本文・まとまったレビュー/検証コメントは [PRとコメントの書き方](docs/pr-writing.md) とrepoテンプレートに従う。本文は概要・何をやったか・なぜ・何をやらなかったか・ユーザーへの影響・検証・その他を埋め、結果/証拠は表にする。公開前とmerge直前に最新本文を検査し、空欄やSHAだけの説明で済ませない。
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
