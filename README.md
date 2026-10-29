# debt-management

日本国内・日本円・BtoB向けマルチテナント債権管理。React / TypeScript / Java 25 / Spring Boot / PostgreSQL のモノレポです。開発は Cloud で行い、開発者の Mac の常時起動には依存しません。

業務要件の入口: [Issue #1](https://github.com/takattty/debt-management/issues/1)。[要件](docs/requirements.md)、[業務例](docs/business-examples.md)、[設計](docs/design.md)、[後続Issueと順序](docs/roadmap.md)、[検証・再開記録](docs/runtime-evidence.md)。

現在は **メール認証・管理者のメンバー招待・匿名受諾・顧客登録/一覧・更新 API** を実装しています。CSV解析とプレビュー保存の内部モデルもあります。請求CSVの画面/確定、入金・消込、督促、貸付元本は未実装で、開発全体の完了ではありません。商用インフラは対象外です。

## 開発用ローカル起動

必要: JDK 25、Node 24.19.0 / npm 11.9以上12未満（推奨11.17.0）、Docker Compose v2、Python3。Gradle Kotlin DSL を使用し、wrapper が Gradle 9.1.0 を SHA256 検証付きで取得します。Spring Boot 4.0.2、PostgreSQL 18.0、React 19.2.0、TypeScript 5.9.3、Playwright 1.56.1 は固定。Docker runtime image は digest を固定しています。CloudのJDKとDocker JREのpatch差は検証記録を参照してください。

```sh
[ -f .env ] || cp .env.example .env
# .env の LOCAL_SEED_PASSWORD を設定してから起動
scripts/build-local.sh
docker compose up --build -d
```

http://localhost:8088 を開きます。架空アカウントは `alpha-admin@example.test` / `beta-admin@example.test` / `alpha-accountant@example.test`、パスワードは `.env` の `LOCAL_SEED_PASSWORD` に明示した値です。未設定・空文字ではComposeが起動を拒否します。メールアドレス/パスワードでDBユーザーを認証し、HttpOnly cookieとCSRFを使用します。架空ユーザーのseedは明示したlocal profileだけで実行し、再起動で既存password/roleを上書きしません。local以外はfail closedです。

管理者でログインし「メンバー招待」でメールアドレスと管理者/経理担当者を選びます。発行したリンクは24時間・一回限りで、管理者が手動共有します。localでは同じマシンの別ブラウザープロファイル等で開き、招待先メールと12〜128文字のpasswordで登録後、明示的にログインします。外部メールは送信しません。既に所属するメールを別tenantへ追加できません。リンクは画面を離れると破棄し、受諾fragmentも即座に履歴から除去します。

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

`verify.sh` はBE lint/compile/unit/実PG統合、FE生成整合・drift否定・lint/type/unit/build・依存audit、runnの実HTTP API、実API＋DBへ接続するheadless E2E、production buildのnative BFCache検証をすべて実行します。BFCache検証は既定ブラウザーのcache無効化を外し、実際の履歴復帰と秘密の非表示を確認します。必須スクリプト欠落や前段失敗はquality失敗です。GitHub Actionsも同じentryを省略せず実行し、public repositoryの標準Linux runnerだけを使用します。Cloudの成功とGitHubの成功を区別して記録します。

## 継続・レビュー・merge

Root / FE / BE の AGENTS.mdと `.agents/skills/delivery-loop/SKILL.md` に従います。実装と独立レビューは別コンテキスト、仕様・ADR・Issue・対象SHAをレビューへ渡します。必須チェック・独立レビュー・Issue受入条件・merge gateの実効性が揃うまでmergeしません。

停止後は [検証・再開記録](docs/runtime-evidence.md) とIssue #1の最新コメントから復元します。常時稼働Coordinatorや `/goal`、停止後自動再起動の可用性は未検証です。

ログインはhash検証前に並行/連続試行を制限します。429の場合はRetry-Afterの秒数を待ってください。email失敗＋in-flightは10、IPは60/60秒、成功時もIP上限はresetしません。単一BEのmemory管理で、再起動時にcounterは失効します。

## HTTP API tests with runn

API受入テストはrunn 1.11.1を使用します。Linux/Darwinのamd64/arm64公式assetのSHA256を固定し、workspace cacheへ取得します。Goの事前導入は不要です。

```sh
RUNN_BASE_URL=http://127.0.0.1:8088 scripts/runn.sh run --scopes read:parent backend/runn/*.yml
```

全runbookで認証/CSRF/tenant/重複/version/JSON型/Unicode、招待の発行/単回受諾/失効等を検証します。OpenAPIレスポンス検証は全runnerで有効。不正入力だけrequest検証を外しserverの拒否を検査します。read:parentはbook外の契約fileを読む最小scopeです。`verify.sh`は今回起動した8080に固定し、このAPI検証後に画面E2Eを実行します。
