# debt-management

BtoB向けマルチテナント債権管理。要件の入口は [Issue #1](https://github.com/takattty/debt-management/issues/1)。

このPRは設計と段階別CIを整えます。アプリは依存PRで追加します。

- [要件](docs/requirements.md)・[業務例](docs/business-examples.md)・[設計](docs/design.md)・[ロードマップ](docs/roadmap.md)
- [GitHub merge gate設定案](docs/github-gate.md)

開発はCloud。React / TypeScript / Java25 / Spring / PostgreSQL、Gradle Kotlin DSL、OpenAPI契約駆動を使用します。設計→契約→BE→FE→自己改善→実行基盤を小さな依存PRで積み、最終基盤PRで実API/DBのheadless E2Eを含む全検証を必須にします。基盤全体の完了条件を満たすまではIssue #1を閉じません。
