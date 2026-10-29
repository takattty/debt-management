---
name: delivery-loop
description: Issue・対象commit・検証・別コンテキストレビューから開発を進行・再開する、このrepository専用の手順。
---

# Delivery loop

この Skill は repository に同梱される。Cloud に個人のローカル Skill が配置済みであるとは仮定しない。ユーザーの指示、root / FE / BE の AGENTS.md を優先する。

## 進行の復元

1. Issue #1、対象 Issue、依存 Issue、最新コメントを読む。未決の業務判断と実行権限を区別する。
2. `git status --short --branch`、`git log -5 --oneline`、対象 PR の head SHA、CI と review の対象 SHA を確認する。未コミット変更や別担当者の所有範囲を保護する。
3. Issue の受入条件と `docs/requirements.md` / `docs/business-examples.md` / ADR の対応を記載する。
4. 次の作業を一つの Issue 範囲へ絞り、検証コマンドと必要な独立レビューを決める。依存条件が未成立なら完了扱いにしない。

## 実装と検証

- 既存のツール固定・ロックファイル・実行スクリプトを使用する。新しいツールや外部サービスを無断で追加課金しない。
- 意味のある正常例と否定例を実行する。実 PostgreSQL の制約・transaction・競合を mock のテストだけで検証済みにしない。
- 各コマンドの結果と対象 SHA を保存する。失敗を修正する際に品質チェックやテストを削除して成功にしない。
- Cloud の検証成功と GitHub Actions 成功を分けて記録する。Actions の利用枠・課金設定が未確認ならその実行を保留する。

## Independent review

Reviewer は実装時の会話を共有しない別コンテキストとする。渡す情報：

- 対象 Issue と受入条件、仕様・業務例・ADR。
- repository、base SHA、対象 head SHA、変更範囲。
- 検証コマンドと結果の保存先。実装担当者の結論をレビュー合格として渡さない。

Reviewer の返却形式は対象 SHA、検査範囲、実行した検証、findings（重大度・根拠箇所・影響）、blocking finding の有無、未検証事項。findings がなくても実施した検査と明示的な結果を残す。

修正後は head SHA を更新し、必要な再レビューと各品質検証を行う。古い SHA の合格を新しい SHA へ流用しない。

## 人向けのPR・コメント

PR作成とまとまった報告の前に [PRとコメントの書き方](../../../docs/pr-writing.md) を読み、repoの本文/コメントテンプレートを使う。

- 本文の7節（概要・何をやったか・なぜそれをやったか・何をやらなかったか・ユーザーへの影響・検証・その他）をすべて埋める。比較候補があれば不採用理由も簡潔に書き、ないものは「なし」と明記する。
- 最終差分の問題と変更後の動作から説明する。生成物と手書き実装、今回完成する範囲と後続を区別する。時系列の実況やコマンド/SHAの羅列を避ける。
- 対象SHA・検証結果・証拠は表へ集約する。未実施は理由を添え、Cloud成功とGitHub CI成功を区別する。
- 公開前に `node .github/scripts/pr-description.cjs --file work/pr-body.md` を実行する。APIは構造化body、CLIはbody-fileで渡す。
- Reviewerは書式だけでなく説明の正確さと読みやすさも確認する。レビューコメントには結論・検査範囲・結果表・残課題を含め、機械用markerだけを投稿しない。
- 本文変更後も必須ゲートの再検査を待ち、merge直前に最新本文を再取得して構造検査する。

## Merge and resume record

merge 前に最新 SHA の必須チェック・独立レビュー・Issue 受入条件を確認する。GitHub の強制 gate を故意の失敗例で実証済みでなければ merge しない。契約・権限・branch protection が不足するときは阻害要因を記録する。

Issue への記録は以下を含む：

```text
対象: branch / SHA / PR
実施済み:
検証済み: コマンド、結果、対象SHA、証拠
独立レビュー: context、対象SHA、blocking findings、再レビュー
未実施:
阻害要因: 実際のエラー・不足権限・必要なユーザー判断
再開: 取得するIssue、チェックアウト、起動、検証、次の操作
```

実行上限による中断と機能完成を区別する。サブエージェントを使った事実は別タスク委譲や停止後再起動の検証にはならない。`/goal` や常時稼働 Coordinator の可用性を推測しない。
