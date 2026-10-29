# ADR-0003: 対象 SHA に結び付く独立レビューと merge gate

Status: Accepted for workflow。GitHub 側の実効性は未検証の間、merge を保留する。

## Context

ユーザーは実装→検証→独立レビュー→修正→再検証→merge の継続開発を承認している。同じ実装会話での自己レビューや、古い SHA の成功を使った merge は条件を満たさない。追加 API 課金は開始せず、GitHub Actions の利用枠を確認する。

## Decision

Issue の受入条件、最新 head SHA、必須 Lint・型・単体・API・実 PostgreSQL 統合・画面→DB の E2E、別コンテキストのレビュー結果を結び付ける。Reviewer には仕様・ADR・Issue・対象 SHA を渡し、実装時の会話を継承させない。Reviewer は blocking finding の解消を明示し、無コメントを合格扱いにしない。

GitHub の契約・権限・branch protection / ruleset によって強制できる条件を確認し、故意の失敗例で CI と review と merge gate が実際に拒否することを示してから、自動 merge を有効にする。満たせないときは merge せず、ブランチ・PR・検証証拠・再開手順を Issue に残す。

## Alternatives

- Coordinator の口頭確認だけで merge：同じ SHA の検証や必須 gate の回避防止を保証できない。
- review comment がない場合に成功とみなす：Reviewer 未実行と区別できない。
- private repository の契約制約を無視して直接 merge：合意済みの強制 gate の条件を満たさない。
- Cloud 検証を Actions 成功として記録：実行環境も証拠も異なり、故意失敗時の挙動を示せない。

## Consequences

GitHub 側の不足によって、実装・Cloud 検証が進んでも Issue を閉じず merge を止める場合がある。制約を避けるために repository を公開したり契約を有料化したりする操作は、ユーザーの明示的な判断と承認を必要とする。プラン変更なしで進める場合も、強制できない gate を実効性確認済みと報告しない。

2026-10-29 の初回確認では repository は private、GitHub Free、`allow_auto_merge=false`。ruleset は Pro への upgrade が必要という 403、branch protection と Actions 権限・請求情報の API も 403。後続の変更や許可は [Issue #7](https://github.com/takattty/debt-management/issues/7) と検証記録に残す。この初回結果を恒久的な最終状態と解釈しない。
