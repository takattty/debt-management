# ADR-0001: Context を分けた modular monolith

Status: Accepted for bootstrap。Context の細部は業務例で継続検証する。

## Context

対象は経理担当者 3 名以下、月間請求 10,000 枚以下の企業。請求訂正と消込、複数請求への入金充当は同じ残高の整合性を要求する。個人開発で Cloud 上の再現・独立レビューを可能にし、未承認の有料インフラを導入しない。

## Decision

FE＋BE のモノレポとし、一つの Spring アプリ、一つの PostgreSQL を初期配置とする。IdentityAndAccess、Billing、Settlement、Collections、後続の Lending の責任境界を明示し、Context 間の更新は application service / port を介す。同じ業務確定に必要な更新と監査を一つの DB transaction に含める。

## Alternatives

- Context ごとに microservice と DB を分離：独立運用は可能だが、消込・請求訂正の競合に分散 transaction や補償が必要となる。現在の規模と実行境界では得られる利益より複雑さが大きい。
- 全機能を単一の CRUD モデルへ統合：着手は早いが、入金事実・請求・回収活動の変更権限と不変条件が混在する。後続の訂正・取消を壊しやすい。
- Event sourcing を全面採用：履歴と再計算に強いが、schema evolution と projection 整合性の負担が大きい。今回は確定記録＋明示的取消＋監査で必要な追跡性を満たす。

## Consequences

一つの配布物としてローカル起動でき、実 PostgreSQL の制約と transaction を品質の一部にできる。一方、Context 境界はコードレビューで維持する必要がある。将来分離する際は現在の強い整合性を再設計し、境界をそのままサービスへ機械的に変換しない。

EX-M03、EX-M08、EX-M09、EX-M10 が境界の妥当性を検証する主要例。
