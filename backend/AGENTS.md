# Backend

- Java 25 / Spring Boot / PostgreSQL を使用する。識別子とログは英語。
- Tenant は認証済みの server-side membership から解決する。入力の tenant header/body を権限根拠にしない。
- SQL には tenant_id 条件を必ず含める。更新は version を比較し、監査記録と同じ transaction で確定する。
- 不変条件は domain と DB constraint の双方に置く。金額は integer JPY または明示した scale の BigDecimal。float は使わない。
- 実 PostgreSQL 統合テストを必須とする。H2 で代替しない。
- 実行: DB_PASSWORD=debt_local ./gradlew --no-daemon check bootJar。
- 本番認証が実装されるまで dev profile 以外では API を denyAll とする。架空アカウントを本番相当の完成扱いにしない。

- Build は Gradle 9.1.0 / Kotlin DSL。Wrapper checksum と dependency lock を維持する。意図した依存更新だけ `--write-locks` で確定し、通常検証で lock を書き換えない。
- `build/libs/debt-management.jar` を起動する。統合テストは専用の架空データ用 DB を使い、顧客と監査テーブルを初期化する。

- API 契約の正本は `../contracts/openapi.yaml`。`openApiGenerate` が Spring interfaces / DTO を `build/generated/openapi` に生成し、controller はその interface を実装する。生成物を直接編集・commit しない。domain 型は transport DTO から分離する。
- API 変更は契約を先に更新し、`./gradlew --no-daemon openApiValidate check bootJar` と FE 側の生成・型検査を確認する。
