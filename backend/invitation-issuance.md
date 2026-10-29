# Invitation issuance only — incremental Issue #2 PR

`POST /api/invitations` だけを追加する。現在の ADMIN が email と ADMIN / ACCOUNTANT を選び、
自 Tenant の24時間有効な手動配布 fragment URL を作る。User / Tenant の ID を本文から受け取らない。
匿名 inspect / accept、受諾画面、メール送信は次 PR の範囲。この段階で招待全体の完成とはしない。

正本から Java interface/DTO と FE schema を生成する。Secret URL は一度だけ応答し、
DB は32randombytes token の SHA-256 hash、audit details は role だけを保存する。
Invitation insert と MEMBER_INVITED audit は同じ transaction。監査失敗で招待も rollback する。
既存 global email の競合は409 INVITATION_UNAVAILABLE とし、他 Tenant の所属情報を返さない。
応答は no-store / no-referrer、生成 DTO の toString も secret を redact する。

実 PostgreSQL / MockMvc は role 選択・Tenant固定・CSRF・権限・secret redaction・監査rollbackを検査する。
runn は全応答OpenAPI検証を維持し、発行の正常/拒否16HTTPケースと従来66ケースを実行する。
