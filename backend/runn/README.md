# Real HTTP API acceptance with runn

既存の全5操作を、実際の HTTP / session cookie / CSRF と PostgreSQL を使って検証する。
`runn` は API 受入テストを担当し、Java の domain / DB / 同時更新テストと画面 E2E も維持する。
架空の dev profile 専用。顧客は実行ごとに `faker.UUID()` で一意なコードを作り、履歴として残す。
DB 初期化や固定 ID の削除に依存しない。各 runbook は同じ concurrency key で順次実行する。

Repository root で、dev Backend と実 PostgreSQL を起動してから実行する。

```sh
RUNN_BASE_URL=http://127.0.0.1:8080 ./scripts/runn.sh run --scopes read:parent backend/runn/*.yml
```

Docker Compose の画面と同じ入口を検証する場合は `RUNN_BASE_URL=http://127.0.0.1:8088`。
既定は `http://127.0.0.1:8080`。架空の `alpha-admin` / `beta-admin` と `debt_local` を使う。
変更する場合は `RUNN_ALPHA_AUTHORIZATION` / `RUNN_BETA_AUTHORIZATION` に Basic header を指定する。
Authorization と token / cookie は runn の `secrets` に指定し、通常実行で debug dump を出さない。

- `customers.yml`: 認証、CSRF、顧客登録・一覧・重複、Tenant 分離、同じコードの別 Tenant 登録、更新・競合、logout と旧 session の拒否。
- `json-types.yml`: number / boolean の String への変換、fraction / String / boolean の integer への変換、UUID 不正を拒否し、一覧から不変を確認。
- `unicode.yml`: 補助文字を含む50 code point の顧客コードと200 code point の名称を登録し、51 / 201 の超過で作成・更新が発生しないことを確認。

各 HTTP runner は正本 `contracts/openapi.yaml` の response validation を有効にする。
正常系 runner は request validation も有効。不正型・超過長・CSRF 欠落等を意図的に送る runner
だけ `skipValidateRequest: true` を指定し、サーバーの拒否そのものを検査する。
この指定で response validation や step assertion を無効化しない。
型・Unicode の不正要求が監査記録を増やさないことは、Java の実 DB 統合テストでも検証する。

構文・Cookie / OpenAPI / bind / faker は [公式 README](https://github.com/k1LoW/runn) と
[公式 cookie example](https://github.com/k1LoW/runn/blob/main/testdata/book/cookie_in_requests_automatically.yml) に従う。
実行 binary と checksum は `scripts/runn.sh` で固定する。

`read:parent` は runbook から `../../contracts/openapi.yaml` を読むためだけに使用する。
Session 開始 GET は `useCookie: false` で新しい cookie を発行させ、以降は cookie jar で返送する。
Logout の POST は runn の media type 必須要件に合わせて 空の JSON object を送り、API は本文を使用しない。

正常な lifecycle 中は runner 間で同じ host の cookie jar を共有する。Tenant は毎回の Basic
principal から決まる。CSRF 欠落の負例は logout 後に置き、拒否応答で発行される cookie が
正常系の live session を上書きしないようにする。旧 session の検査は取得済み cookie/token を
明示して送り、失効を確認する。

3 runbooks、37 defined steps、loop を展開して39 HTTP response assertions。
