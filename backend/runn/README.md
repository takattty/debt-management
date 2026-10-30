# Real HTTP API acceptance with runn

Cookie session 認証と顧客管理の全7操作を、実 HTTP / 実 PostgreSQL で検証する。
`runn` が API 受入を担当し、Java の domain / DB / 同時更新テストと画面 E2E も維持する。
明示 `local` profile の架空データ専用。既存の顧客39ケースを維持し、匿名 CSRF と login、
session fixation / logout / role / 旧 Basic 拒否を加えた4 runbooks・66 HTTP assertions。

Repository root で local Backend と実 PostgreSQL を起動し、起動時と同じ
`LOCAL_SEED_PASSWORD` を shell 環境へ設定して実行する。Password に既定値はない。
Local 初期化用の架空 email は `alpha-admin@example.test`、`beta-admin@example.test`、
`alpha-accountant@example.test`。Basic header は login に使用しない。

```sh
RUNN_BASE_URL=http://127.0.0.1:8080 ./scripts/runn.sh run --scopes read:parent backend/runn/*.yml
```

Docker Compose の入口は `RUNN_BASE_URL=http://127.0.0.1:8088`。
顧客コードは実行ごとに `faker.UUID()` で一意にし、実行した顧客は履歴として残す。
DB 初期化や固定 ID の削除に依存せず、同じ concurrency key で順次実行する。
Password / token / cookie を `secrets` に指定し、通常実行で debug dump を出さない。

- `authentication.yml`: 匿名 CSRF、email login、session ID 更新、古い匿名 cookie/CSRF の拒否、generic な認証失敗、Basic 拒否、再読込での User 復元、logout、Accountant の業務操作。
- `customers.yml`: 顧客登録・一覧・重複、Tenant 分離、同じコードの別 Tenant 登録、更新・競合、CSRF と logout。取得した別々の cookie を明示して Alpha / Beta の session を独立して扱う。
- `json-types.yml`: number / boolean → String、fraction / String / boolean → integer、UUID 不正を拒否し、一覧から不変を確認。
- `unicode.yml`: 補助文字を含む50 code point のコード・200 code point の名称を登録し、51 / 201 の超過で作成・更新が発生しないことを確認。

各 HTTP runner は正本 `contracts/openapi.yaml` の response validation を有効にする。
正常系は request validation も有効。不正型・超過長・資格情報/CSRF 欠落を意図して送る runner
だけ `skipValidateRequest: true` とし、サーバーの拒否を検査する。response validation や
step assertion を外さない。監査の原子性・秘密を記録しないことは Java の実 DB 統合検証でも確認する。

`read:parent` は `../../contracts/openapi.yaml` を読むためだけに使用する。
匿名 CSRF 開始 GET は `useCookie: false` で新しい session を発行させる。
Login の更新 cookie は runn の cookie jar が引き継ぎ、GET session の新しい CSRF を後続処理に使う。
複数 Tenant を扱う runbook では同じ host の jar を共有する runn の性質に合わせ、
Cookie を明示して各 session を取り違えない。Mock の cookie / token には置き換えない。
Logout の POST は runn の media type 要件に合わせて空の JSON object を送り、API は本文を使わない。

構文・Cookie / OpenAPI / bind / faker は [公式 README](https://github.com/k1LoW/runn) と
[公式 cookie example](https://github.com/k1LoW/runn/blob/main/testdata/book/cookie_in_requests_automatically.yml) に従う。
Binary と checksum は `scripts/runn.sh` で固定する。

`customer-import.yml`は顧客CSVのmultipart upload、部分登録ゼロ、行のページ取得、全error/download、修正後の新previewと旧snapshot保持を実HTTPで検証する。previewは業務行を登録せず、別の明示confirmで全件登録する。原子的確定・応答喪失時の再送・順序/改行を変えた再取込拒否・元の取込結果の参照も検証する。
`scripts/runn.sh run`は`prepare-runn-fixtures.py`で実行ごとにUUIDの架空コードを持つCSVをignoredな`work/runn-customer-*.csv`へ作る。追加のexec scopeや既存DBの初期化を必要としない。固定ファイルの再使用で過去の登録が新しい検証へ混入しないようにする。

CIのqualityはbootstrapの部分検証fallbackを廃止し、scripts/verify.shを必須実行する。verifier消失や非実行権限を部分成功に置き換えない。
