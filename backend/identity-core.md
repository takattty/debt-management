# Passive identity persistence core — Issue #2 first PR

この PR は schema / value objects / read repository だけを追加する。
新しい公開 API・User seed・SecurityFilterChain・PasswordEncoder bean は追加しない。
既存 dev Basic 認証・13テスト・3つのrunnを維持し、cookie login は後続の同一縦断 PR で切り替える。

- `app_user`: 必須 Tenant FK、globally unique canonical email、Argon2id hash、ADMIN / ACCOUNTANT、enabled、version。
- `audit_event.actor_user_id`: nullable な追加。新規 User 監査は Tenant と組み合わせた FK で越境を拒否できる。
  既存の dev actor 文字列と履歴は保持し、過去の記録に架空の User ID を付けない。
- Email は前後空白除去・ASCII・lowercase・最大254文字。
- Password 作成 policy は12〜128 code points / UTF-8最大512bytes。Login は非空〜最大長の検査のみ。
  trim / Unicode 正規化しない。BC1.83 を固定し、Argon2id の salt・72bytes以降も異なるpasswordを検査する。
- `UserAccount` は principal と独立した永続化の snapshot。toString に password hash を含めない。
  後続 cookie auth は UserAccount → session principal の変換を access adapter 側で実装する。

Core の実 PostgreSQL テストは専用 DB を使い、自分で作った UUID の行だけを後片付けする。
Global email 一意・同時 INSERT の一勝者・Tenant / role / hash 制約・監査 FK と legacy 互換を確認する。
Runtime の PasswordEncoder はまだ従来の dev BCrypt。Argon2id への切替は後続の auth PR で行う。
