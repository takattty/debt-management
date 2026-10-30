# Backend

- Java 25 / Spring Boot / PostgreSQL を使用する。識別子とログは英語。
- Java source名を責務から推測しない。rootの `scripts/read-source.py QUERY --list` で同checkoutの実catalogを別callで観測し、次callでその実pathを読込む。別checkoutは `--worktree` を明示する。
- Tenant は認証済みの server-side membership から解決する。入力の tenant header/body を権限根拠にしない。
- SQL には tenant_id 条件を必ず含める。更新は version を比較し、監査記録と同じ transaction で確定する。
- 不変条件は domain と DB constraint の双方に置く。金額は integer JPY または明示した scale の BigDecimal。float は使わない。
- 実 PostgreSQL 統合テストを必須とする。H2 で代替しない。
- 実行: DB_PASSWORD=debt_local ./gradlew --no-daemon check bootJar。
- 明示 `local` profile でのみ runtime の session API と架空 seed を有効化する。`LOCAL_SEED_PASSWORD` は必須、既定値なし。`auth-test` は専用 DB fixture を使う JUnit 専用で seed なし。それ以外の profile は API を denyAll。商用 identity 設定を完了扱いにしない。

- Build は Gradle 9.1.0 / Kotlin DSL。Wrapper checksum と dependency lock を維持する。意図した依存更新だけ `--write-locks` で確定し、通常検証で lock を書き換えない。
- `build/libs/debt-management.jar` を起動する。統合テストは専用の架空データ用 DB を使い、顧客と監査テーブルを初期化する。

- API 契約の正本は `../contracts/openapi.yaml`。`openApiGenerate` が Spring interfaces / DTO を `build/generated/openapi` に生成し、controller はその interface を実装する。生成物を直接編集・commit しない。domain 型は transport DTO から分離する。
- API 変更は契約を先に更新し、`./gradlew --no-daemon openApiValidate check bootJar` と FE 側の生成・型検査を確認する。

- 認証は email/password → HttpOnly cookie session。Basic を再導入しない。Login/変更/logout に CSRF を要求し、login は session ID と CSRF を更新する。role/tenant/enabled は毎 request DB から再確認する。session principal と audit に password/hash/token を保持しない。
- Argon2id は19MiB / iterations2 / parallelism1、salt16/hash32 bytes。Password 作成は12〜128 code pointsかつUTF-8最大512bytes。Login は既存 credentials の検証なので下限は非空のみ。trim/Unicode正規化しない。
- CSRF 拒否は `CSRF_INVALID`、role 等の拒否は `ACCESS_DENIED`、認証失効は401 `UNAUTHENTICATED`。権限不足とsession失効をクライアントが区別できる状態を維持する。
