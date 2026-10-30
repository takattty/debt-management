# debt-management

日本国内・日本円・BtoB向けマルチテナント債権管理。React / TypeScript / Java 25 / Spring Boot / PostgreSQL のモノレポです。開発は Cloud で行い、開発者の Mac の常時起動には依存しません。

業務要件の入口: [Issue #1](https://github.com/takattty/debt-management/issues/1)。[要件](docs/requirements.md)、[業務例](docs/business-examples.md)、[設計](docs/design.md)、[後続Issueと順序](docs/roadmap.md)、[検証・再開記録](docs/runtime-evidence.md)。

売掛金の操作対象は **ログイン/手動リンク招待、顧客・請求・入金CSVの確認/全件確定、名義対応、理由付き消込、一部入金・複数顧客・手数料・過入金、取消/請求訂正/履歴、滞留・次回対応・保留・引継ぎ・メール草案コピー** です。[操作手順と架空CSVサンプル](docs/user-guide.md)を参照してください。

現在のmainは入金・金融機能まで順次統合中で、回収を含む全ARのsource統合と最終受入は継続中です。prepared `6404ccc9dd41898b18506762e91e35bbdc20151b` の実DB・画面・Compose実証を、最終mainでの再実行や完成と混同しません。[検証対象と結合待ちの状態](docs/user-guide.md#検証対象と最終mainへの結合)を確認してください。

今回の作業は売掛金の仕上げまでで停止します。貸付元本は保留し、Loanへ自動継続しません。メール送信、利息計算、商用インフラは対象外です。[入金修正例](docs/samples/payments-corrected.csv)・[エラー例](docs/samples/payments-invalid.csv)・[対応する請求](docs/samples/invoices-ar.csv)は架空データだけを含みます。

## 開発用ローカル起動

必要: JDK 25、Node 24.19.0 / npm 11.9以上12未満（推奨11.17.0）、Docker Compose v2、Python3。Gradle Kotlin DSL を使用し、wrapper が Gradle 9.1.0 を SHA256 検証付きで取得します。Spring Boot 4.0.2、PostgreSQL 18.0、React 19.2.0、TypeScript 5.9.3、Playwright 1.56.1 は固定。Docker runtime image は digest を固定しています。CloudのJDKとDocker JREのpatch差は検証記録を参照してください。

```sh
[ -f .env ] || cp .env.example .env
# .env の LOCAL_SEED_PASSWORD を設定してから起動
scripts/build-local.sh
docker compose up --build -d
```

http://localhost:8088 を開きます。架空アカウントは `alpha-admin@example.test` / `beta-admin@example.test` / `alpha-accountant@example.test`、パスワードは `.env` の `LOCAL_SEED_PASSWORD` に明示した値です。未設定・空文字ではComposeが起動を拒否します。メールアドレス/パスワードでDBユーザーを認証し、HttpOnly cookieとCSRFを使用します。架空ユーザーのseedは明示したlocal profileだけで実行し、再起動で既存password/roleを上書きしません。local以外はfail closedです。

管理者は「メンバー招待」でリンクを作成し、相手へ手動共有します。外部メールは送信しません。招待の受諾からログインまでの流れと、有効期限などの制約は[操作手順](docs/user-guide.md#ログインとメンバー招待)を参照してください。

```sh
docker compose down
# 開発DBのデータを消す場合だけ: docker compose down -v
```

build-local は成果物の作成用で、テスト成功の証拠にはなりません。merge前には以下を必ず実施します。

起動後は[売掛金の操作手順](docs/user-guide.md)で架空データを登録できます。売掛金全体のprepared実証と停止・再作成時の17table/12,722行保持は、同ガイドの検証表にまとめています。最終mainの304入力・JAR・dist・runtimeへの結合確認は後続です。

managed CloudのVFSでは、container作成でimage全体の複製が必要です。Docker検証は他の重いworkerを止め、`scripts/docker-verify.sh --cloud-local` を使います。実測したimage・build入力・既存container・PG起動余裕に1GiBの空きを残せない場合や、測定不能の場合は作成前に停止します。Cloudでは固定したbase imageを事前に用意し、不足時は所有者と復旧方法を確認してください。guardはpull・cleanup・daemon設定変更を行いません。通常のMac/Desktop/remoteではこのmodeを使わず、上の起動手順と選択済みDocker contextをそのまま使います。

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

品質検証では標準LinuxのDocker socketを使い、本番Nginxを通すrunnも必須実行します。1万行CSV・10MiBのファイル境界・proxy側の容量超過を検証し、413はJSONで返します。ファイル上限は10MiB、request上限はmultipartの余白を含め11MiBです。

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
