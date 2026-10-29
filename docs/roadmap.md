# Delivery roadmap

[Issue #1](https://github.com/takattty/debt-management/issues/1) は開発全体の入口。基盤だけで終了せず、売掛金の必須機能とローカル起動可能な品質をそろえ、その後に貸付元本へ進む。下表は実装順であり、作成済み Issue や文書は機能完成を意味しない。

| 段階・Issue | 依存関係 | 主な受入範囲 |
| --- | --- | --- |
| [#1 Bootstrap](https://github.com/takattty/debt-management/issues/1) | なし。merge は #7 の解決を要する | 要件・業務例・ADR、Cloud の対応ツール固定、最小縦断と各検証、独立レビュー、失敗時に止まる gate、再開可能な証拠 |
| [#7 GitHub gate / budget](https://github.com/takattty/debt-management/issues/7) | #1 と並行 | プラン/公開範囲/Actions 残枠・超過課金設定・権限の確認、強制 gate の実証。未解決なら merge 保留 |
| [#2 Identity / invitations / audit](https://github.com/takattty/debt-management/issues/2) | #1 | 認証、1 user 1 tenant、管理者招待、役割、業務監査、tenant 越境否定例 |
| [#3 Customer / Invoice CSV / receivables](https://github.com/takattty/debt-management/issues/3) | #1、#2 | 顧客登録→CSV preview→エラー修正・再取込→全件確定→債権一覧、訂正・取消・重複・競合 |
| [#4 Payment / reconciliation](https://github.com/takattty/debt-management/issues/4) | #2、#3 | 架空銀行 CSV、候補理由、担当者確定、一部/一括/手数料/過入金/取消、残高の保存則 |
| [#5 Collections](https://github.com/takattty/debt-management/issues/5) | #3、#4 | 未収・滞留、対象一覧、対応履歴、次回対応日、保留理由、メール文面、送信なし |
| [#6 LoanPrincipal](https://github.com/takattty/debt-management/issues/6) | #2、#3〜#5 の必須機能成立 | 貸付先・貸付日・当初元本・追加貸付・元本返済・取消履歴。利息計算なし |

## 各段階で共通する完了条件

- 実装範囲に対応する `Rxx` と `EX-xxx` を Issue / PR に記載し、画面と API の両方で不変条件を守る。
- 最新 SHA の Lint、型、単体、API、実 PostgreSQL 統合、画面→DB E2E を実行する。tenant 分離・競合・取消・確定後制限・重複を適用範囲ごとに検証する。
- 別コンテキストに仕様・ADR・Issue・SHA を渡して独立レビューを行い、blocking finding を解消して再検証する。
- 実効性を確認した merge gate を通過させる。レビュー後に変更したときは対象 SHA を更新して必要な検証とレビューをやり直す。
- Docker ローカル起動手順、架空データ、エラー復旧導線を機能とともに更新する。

## 継続と停止

Coordinator は常時稼働プロセスを前提にせず、Issue の状態・依存関係・対象 commit・CI・レビューから次の作業を復元する。未決の業務判断は質問し、依存しない実装や検証を継続する。

契約・権限・実行上限で停止した場合は、対象ブランチ、SHA、PR、実施済み/検証済み/未実施、阻害要因、再開コマンドを Issue #1 と対象の後続 Issue に残す。まだ成立していない基盤や未解決 gate の存在を隠して後続 Issue を完了扱いにしない。依存する merge が止まる間の設計・ブランチ上の試作は明示して分ける。
