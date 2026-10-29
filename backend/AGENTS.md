# Backend

- Java 25 / Spring Boot / PostgreSQL を使用する。識別子とログは英語。
- Tenant は認証済みの server-side membership から解決する。入力の tenant header/body を権限根拠にしない。
- SQL には tenant_id 条件を必ず含める。更新は version を比較し、監査記録と同じ transaction で確定する。
- 不変条件は domain と DB constraint の双方に置く。金額は integer JPY または明示した scale の BigDecimal。float は使わない。
- 実 PostgreSQL 統合テストを必須とする。H2 で代替しない。
- 実行: DB_PASSWORD=debt_local SPRING_PROFILES_ACTIVE=dev ./mvnw verify。
- 本番認証が実装されるまで dev profile 以外では API を denyAll とする。架空アカウントを本番相当の完成扱いにしない。
