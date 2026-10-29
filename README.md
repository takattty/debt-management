# debt-management

日本国内・日本円・BtoB向けマルチテナント債権管理。React / TypeScript / Java 25 / Spring Boot / PostgreSQL のモノレポです。開発は Cloud で行い、開発者の Mac の常時起動には依存しません。

業務要件の入口: [Issue #1](https://github.com/takattty/debt-management/issues/1)。[要件](docs/requirements.md)、[業務例](docs/business-examples.md)、[設計](docs/design.md)、[後続Issueと順序](docs/roadmap.md)、[検証・再開記録](docs/runtime-evidence.md)。

現在の実装範囲は **顧客登録・一覧・更新 API の縦断基盤** です。請求CSV、入金・消込、督促、メンバー招待、貸付元本は後続Issueです。機能完成・本番運用可能という意味ではありません。商用インフラは対象外です。

## 開発用ローカル起動

必要: JDK 25、Node 24.19.0 / npm 11.9.0、Docker Compose v2、Python3。Gradle Kotlin DSL を使用し、wrapper が Gradle 9.1.0 を SHA256 検証付きで取得します。Spring Boot 4.0.2、PostgreSQL 18.0、React 19.2.0、TypeScript 5.9.3、Playwright 1.56.1 は固定。Docker runtime image は digest を固定しています。CloudのJDKとDocker JREのpatch差は検証記録を参照してください。

```sh
scripts/build-local.sh
docker compose up --build -d
```

http://localhost:8088 を開きます。架空アカウントは `alpha-admin@example.test` / `beta-admin@example.test` / `alpha-accountant@example.test`、架空fixtureのパスワードは `debt_local_demo_2026`。`.env.example` を `.env` にコピーして変更できます。メールアドレス/パスワードでDBユーザーを認証し、HttpOnly cookieとCSRFを使用します。架空ユーザーのseedは明示したlocal profileとLOCAL_SEED_PASSWORDが必要です。local以外はfail closedです。招待はIssue #2の次PRで実装します。

```sh
docker compose down
# 開発DBのデータを消す場合だけ: docker compose down -v
```

build-local は成果物の作成用で、テスト成功の証拠にはなりません。merge前には以下を必ず実施します。

## Cloud / 開発検証

実PostgreSQLに接続します。H2やAPIモックで統合/E2Eを代替しません。検証は専用の架空データDBで行い、テストは対象テーブルを初期化します。

```sh
docker run -d --name debt-postgres \
  -e POSTGRES_DB=debt -e POSTGRES_USER=debt -e POSTGRES_PASSWORD=debt_local \
  -p 127.0.0.1:5432:5432 postgres:18.0
# Java 25をJAVA_HOMEとPATHへ設定した上で:
CHROMIUM_PATH=/usr/bin/chromium scripts/verify.sh
```

Chromiumがない環境では `cd frontend && npm ci && npx playwright install --with-deps chromium` を実行し、CHROMIUM_PATHを指定せず検証します。CloudのGradle proxy/CA設定は `scripts/toolchain-env.sh` が既存の非秘密pathを使用します。証明書検証は無効化しません。Docker非対応Cloudの直接PG起動スクリプト `scripts/postgres-cloud.sh` は用意していますが、この環境はDocker対応のためfallback実行は未検証です。

`verify.sh` は BE lint/compile/unit/API/実PG統合、FE lint/type/unit/build、実API＋DBへ接続するheadless E2Eを順に実行します。GitHub Actionsはpublic repositoryの標準Linux runnerのみ使用します。Cloudの成功とGitHubの成功を区別して記録します。

## 継続・レビュー・merge

Root / FE / BE の AGENTS.mdと `.agents/skills/delivery-loop/SKILL.md` に従います。実装と独立レビューは別コンテキスト、仕様・ADR・Issue・対象SHAをレビューへ渡します。必須チェック・独立レビュー・Issue受入条件・merge gateの実効性が揃うまでmergeしません。

停止後は [検証・再開記録](docs/runtime-evidence.md) とIssue #1の最新コメントから復元します。常時稼働Coordinatorや `/goal`、停止後自動再起動の可用性は未検証です。

## HTTP API tests with runn

API受入テストはrunn 1.11.1を使用します。Linux/Darwinのamd64/arm64公式assetのSHA256を固定し、workspace cacheへ取得します。Goの事前導入は不要です。

```sh
RUNN_BASE_URL=http://127.0.0.1:8088 scripts/runn.sh run --scopes read:parent backend/runn/*.yml
```

3シナリオ39HTTPリクエストで、認証/CSRF/tenant/重複/version/JSON型/Unicode/取消されたsessionを検証します。OpenAPIレスポンス検証は全runnerで有効。read:parentはbook外の契約fileを読む最小scopeです。`verify.sh`は今回起動した8080に固定し、このAPI検証後に画面E2Eを実行します。
