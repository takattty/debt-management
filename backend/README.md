# Cookie session authentication — first Issue #2 slice

`local` profile を明示した HTTP localhost のみ、DB User による email/password login と
架空の初期 User を有効にする。未指定を含むその他の runtime profile は全 API を拒否する。
`auth-test` は JUnit 専用で、明示した実 DB fixture を作る。local seed を使わず、runtime に指定しない。
商用 identity 設定、招待・role 管理、recovery、試行制限はこの最初の slice の完了対象ではない。

Java 25 / Gradle 9.1.0。起動前に PostgreSQL への `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` を設定する。
`local` の `LOCAL_SEED_PASSWORD` は必須で既定値なし。作成時12〜128 code points、UTF-8最大512bytes。
初回だけ各架空 User を作り、再起動時に password hash・role・有効状態を上書きしない。
既存 fixture identity と Tenant / ID / role が衝突すれば起動を拒否する。

```sh
./gradlew --no-daemon check bootJar
SPRING_PROFILES_ACTIVE=local java -jar build/libs/debt-management.jar
```

Login は GET `/api/csrf` → POST `/api/auth/login` → GET `/api/session`。
Login 自体にも CSRF が必要。成功時は session ID と CSRF token を更新するため、古い値を再利用しない。
HTTP cookie は HttpOnly / SameSite=Lax / Path=/、inactivity30分。Secure=false は local のみ。
User / Tenant / role / enabled は毎 request DB から確認し、session 内の古い権限を根拠にしない。
Basic 認証を受け付けず、password / hash は session principal に保持しない。

Password は trim / Unicode 正規化せず Argon2id で保存する。
Salt16bytes・hash32bytes・memory19MiB・iterations2・parallelism1、暗号依存は固定した Bouncy Castle1.83。
未登録 email と誤 password は同じ401にし、未登録でも dummy hash を検証する。
成功した login / logout と顧客変更を、stable user ID と Tenant の FK を持つ監査記録へ残す。
Password / hash / CSRF / session ID は監査 details に記録しない。既存 dev actor 文字列は過去履歴として保持する。

CSRF 拒否は403 `CSRF_INVALID`、認証失効は401 `UNAUTHENTICATED`、権限拒否は403 `ACCESS_DENIED`。
未認証や失効時は再ログインし、権限拒否で有効な session を勝手に破棄しない。
