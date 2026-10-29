# IdentityAndAccess implementation plan — Issue #2

Status: email/cookie sessionと管理者招待・匿名受諾のAPI/画面を実装・独立レビュー・CI後に統合済み（#19/#20/#21/#27/#28/#29）。試行制限も #30 で独立レビュー・全CI後に統合済み。
根拠: Issue #1/#2、requirements R16〜R22、EX-A01〜04、ADR-0001/0003。
範囲: 架空データ・ローカル Docker。商用インフラ、追加 API 課金、メール送信サービスなし。

## 合意済みの業務判断

- login ID は email＋password、1 User は 1 Tenant、役割は Administrator / Accountant。
- Administrator が有効期限付き・一回限りの招待リンクを発行し、手作業で相手へ共有する。追加 Administrator と Accountant の両方を招待できる（ユーザー回答済み）。
- Accountant は取込・消込・取消を単独で確定できる。別担当者承認は初期版に要求しない。
- 取引先ログインはなく、利用者がリクエスト本文で Tenant を選択・切替する機能はない。
- 貸付以外を含む業務データの境界は、認証された所属から求める。

## 現行からの移行

旧dev profileの固定Basic principalは廃止済み。POST `/api/auth/login`、GET `/api/session`、POST `/api/logout`、CSRFをDB Userとcookie sessionへ接続した。
FEのpassword常時保持も廃止し、招待fragment/発行link/passwordはpagehideで同期破棄、BFCache復帰時はURL/cookieから新たに起動する。
正本 `contracts/openapi.yaml` を先に更新し、Java interface/DTO と TypeScript 型を再生成する。
既存 Customer API の tenant 分離・型拒否・競合・audit の検査を維持し、runn の login 部分だけ新方式へ移す。
logout 後の旧 cookie 拒否と CSRF 保護を引き継ぐ。Basic header だけではログインできない否定例も追加する。

## Use cases と境界

| Use case | 実行者 | 結果・境界 |
| --- | --- | --- |
| StartLogin | Anonymous | CSRF を取得し、email/password を検証して新しい session ID を発行 |
| GetCurrentSession | Authenticated | DB の有効 User と所属から actor/role/tenant、CSRF を返す |
| Logout | Authenticated | session と SecurityContext を失効。古い cookie を再利用できない |
| InviteMember | Administrator | 自 Tenant の招待を発行。平文 token は発行応答で一度だけ返す |
| InspectInvitation | Anonymous | token の有効性と最小表示情報を確認。全メンバーや他 tenant の情報は返さない |
| AcceptInvitation | Anonymous | 招待・email・password を検証し、同一 transaction で User 作成と招待消費 |
| RecordAudit | Application service | 招待・受諾・業務変更と同一 transaction に成功イベントを保存 |

IdentityAndAccess は認証と所属を所有する。Customer 等へは immutable Actor(userId,tenantId,role) を渡す。
DB User の有効性・所属を各 request で確認し、session 内の古い role/tenant を単独の根拠にしない。

## 不変条件と永続化

- email はグローバルで一意。別 Tenant への再所属も拒否し、User が二つの Tenant に属さない。
- User.tenantId は NOT NULL。任意のリクエスト値による所属の変更を受け付けない。
- 招待の Tenant と付与 role は発行時に固定し、受諾本文による変更を受け付けない。Administrator の発行時 role 選択だけが追加管理者の権限付与になる。
- token は 32 random bytes の base64url、保存は SHA-256 hash のみ。検索は hash、平文はログ・audit・DB に残さない。
- 同じ招待の受諾は一回。期限・消費状態を transaction 内で再検証する。
- 同 email の並行受諾は unique 制約で一方だけ成功。User 作成失敗なら招待消費・audit も rollback。
- 同じ token の並行受諾は row lock または条件付き UPDATE で一方だけ成功する。
- 同じ email に複数の有効招待があっても User は一つだけ。発行時の既存 User 検査に加えて受諾時に再検証する。
- 拒否された業務操作は成功 audit を生成しない。認証失敗ログは成功監査と分離し、秘密を含めない。

| Table | 主な列・制約 |
| --- | --- |
| app_user | id UUID PK, tenant_id FK, email_canonical UNIQUE, email_display, password_hash, role CHECK, enabled, version, created_at |
| invitation | id UUID PK, tenant_id FK, email_canonical, role CHECK, token_hash UNIQUE, expires_at, accepted_at NULL, accepted_user_id NULL, created_by_user_id, created_at |
| audit_event | 既存を migration。actor の100文字制限へemailを押し込まず、actor_user_id UUID と参照可能なevent主体を持つ |
| session | 初期案は Spring Security servlet session（単一 BE）。DB User 有効性を毎回再確認し、BE 再起動時は再ログイン |

既存 dev audit の actor は履歴として保持し、新規 audit は安定した user ID を記録する。移行で過去記録を偽の実ユーザーへ置換しない。
Invitation.created_by の参照は tenant を含む複合 FK または同一 transaction の所属検証で越境を防ぐ。
InvitationAccepted / MemberInvited / SessionStarted / SessionEnded は監査上の概念。message broker は導入しない。

## 技術上の初期値と理由（業務合意とは区別）

- email: 前後空白除去、ASCII email、全体を lowercase した canonical ID、最大254文字。配送可否を外部検証しない。
- password: Argon2id（Spring Security、salt 16 bytes、hash 32 bytes、memory 19 MiB、iterations 2、parallelism 1）。
  BCrypt の72-byte切捨てを避けるため選択。必要な暗号依存はバージョン固定し、実測で負荷を確認する。
- 新しい password は 12〜128 code points、UTF-8 最大512 bytes、trim/Unicode正規化しない。空白も文字として扱い、FE/BEを一致させる。
- 招待期限は24時間、Clock 注入で境界を検証。再発行は新 token とし、元 token を再利用しない。
- cookie は HttpOnly / SameSite=Lax / Path=/。Secure は HTTPS 構成で必須、明示 local profile の HTTP localhost のみ例外。
- session は30分 inactivity、login成功時にID変更、logout時に完全失効。認証・token応答は Cache-Control:no-store。
- CSRF は session に結び付け、login・招待照会/受諾・logoutを含む全POSTへ適用。login後は新 token を取得する。
- CORS は同一 origin のみ。CSRF token や X-Requested-With だけを認証の代替にしない。
- 認証失敗は未登録/誤passwordを区別しない401、dummy hash検証を行う。短時間の試行制限を設け429を検証する。
  初回sliceで後続扱いにした試行制限は #30 で実装。emailの失敗＋in-flight10、IP60/60秒、容量1万key、Retry-After1〜60秒。成功時はemail失敗だけresetし、新cookie/XFFで回避できない。単一BEのmemory管理で、再起動時にcounterは失効する。
- 招待invalid/expired/usedは同じ公開エラー。受諾時email競合も他Tenantの所属情報を返さない。
- 共有リンクは `/accept-invitation#token=...`。FEがfragmentから読んでhistory.replaceStateで消し、tokenはPOST本文のみ送る。
  Referrer-Policy:no-referrer、第三者scriptなし、localStorage不使用。管理者は自分で共有先を確認する。
- 手動リンクの所持は招待受諾の権限であり、email配送による本人確認を実装済みとは主張しない。

## API contract 案

| Method / path | 入力と応答 | 主な拒否 |
| --- | --- | --- |
| GET /api/csrf | Anonymous可、csrfToken/headerName、session cookie | closed profile 403 |
| POST /api/auth/login | email,password + CSRF → 204、session cookie変更 | 400 / 401 / 403 / 429 |
| GET /api/session | userId,email,role,tenantId,csrfToken/headerName → 200 | 401 |
| POST /api/logout | CSRF → 204 | 401 / 403 |
| POST /api/invitations | admin、email,role + CSRF → 201、id,expiresAt,acceptanceUrl | 400 / 401 / 403 / 409 |
| POST /api/invitations/inspect | Anonymous、token + CSRF → 200、tenantDisplayName,maskedEmail,role,expiresAt | 400 / 403 |
| POST /api/invitations/accept | Anonymous、token,email,password + CSRF → 201、完了結果 | 400 / 403 / 409 |

受諾後は自動ログインせずlogin画面へ案内する。URLやログへpassword/tokenを含めない。OpenAPIにcookie authとCSRF headerを明示する。
業務APIの未認証は401、認証済み権限不足は403、他Tenantの個体指定は404として存在を隠す。
入力上限と不正型は正本から生成し、未知のtenantId/role混入を許容しないcontract検査を追加する。

## Local provisioning / fail closed

架空adminを作る処理は明示 local profile のみ。email/tenantは架空fixtureを固定し、LOCAL_SEED_PASSWORDを明示必須にしてpassword既定値を持たない。
初回だけUserを作成し、再起動で既存passwordやroleを上書きしない。衝突時は失敗して診断する。
非local profileではseedを実行しない。運用identity設定がない正式profileはAPIをdenyAllまたは起動失敗し、既定公開accountを作らない。
テストprofileは明示fixtureを用意し、local seedや固定Basicに依存しない。DB永続化後の再起動も同じUserでログインできる。

## 受入例と検証計画

| ID | 操作 | 期待結果・検証層 |
| --- | --- | --- |
| IAM01 | local起動→email login→Customer登録→logout | 実FE→BE→PG。再読込でUser復元、旧cookie拒否、旧Basic拒否 |
| IAM02 | adminがaccountant招待→手動リンク受諾→login | Userが発行Tenantへ所属。accountantの顧客操作は可、招待POSTは403 |
| IAM03 | 同tokenを二回/並行受諾、期限ちょうど・直後 | 一回のみ成功、期限境界拒否。User/消費/auditの一貫性をJUnit実PGで確認 |
| IAM04 | 異なるTenantの招待を同emailで並行受諾 | global UNIQUEにより一所属のみ。失敗側は全rollback。他Tenant情報を非公開 |
| IAM05 | 他Tenant ID混入、他Tenant Customer操作 | 本文指定で所属が変わらず、既存越境否定例を維持 |
| IAM06 | login/inspect/accept/logoutでCSRF欠落・旧token | 403、User/招待消費/業務audit不変。login後session ID更新 |
| IAM07 | DB audit insertを故意に失敗させ招待/受諾 | transaction rollback。token hashのみ保存、audit/logに秘密がない |
| IAM08 | password/hash/招待入力境界と正規化 | 単体＋API。password無断trimなし、email大文字重複拒否、型/長さ拒否 |
| IAM09 | local password未設定、正式profile未設定、再起動 | 安全な起動拒否/denyAll、公開既定accountなし、既存User上書きなし |
| IAM10 | adminが追加admin招待→受諾→新adminがaccountant招待 | 同じTenantで招待可。accountantのrole指定改ざんでは権限昇格できない |

runnは実HTTPのcookie jarとOpenAPI request/response検証を使用。匿名CSRF→login→session→admin招待→別cookie jarでinspect/accept→accountant login→越権拒否→logoutを実行する。
JUnitは実PostgreSQL、Clock固定、barrier付き並行requestでIAM03/04を反復し、User/invitation/audit件数と所属を照合する。
FE E2Eは実BE/PGでIAM01/02/05/06を操作し、招待エラー・期限切れ・単独確定の役割表示を確認。mockだけで受入完了にしない。
必須Lint・型・runn API・BE/FEの全単体/統合/E2EをCIで実行し、別contextレビューを同SHAに結び付ける。段階実装でも既存検査を全維持し、失敗テストやgateを削除・skipしない。

## 小さい3本のPRに分ける実装順と所有範囲

| 順序 | PRの完結する動作 | 所有範囲・受入条件 |
| --- | --- | --- |
| 1: DB auth/sessionの最小縦断 | DB User/schema、email login、cookie session、logout、local seed、fail-closed。Customer登録まで既存画面が動く | Access担当: migration/access/domain/単体・実PG。Contract担当: OpenAPI正本と生成更新。FE担当: login/session/apiと既存Customer画面。検証担当: 既存runn/E2Eのlogin移行とIAM01/05/06/08/09 |
| 2: Invitations/role/audit API | admin/accountant招待、inspect/accept、hash/期限/単回・並行・global email一意、招待権限、audit原子性 | Access担当: invitation/application/persistence/監査。Contract担当: 招待操作の正本・生成。検証担当: 新規runnと実PGのIAM02〜04/06〜08/10。画面導線はPR3で追加し、この時点で招待UI完了とはしない |
| 3: 招待画面と全体受入 | 管理者のrole選択・リンク共有、匿名受諾、期限/再使用エラー、role表示。全体のFE→DB業務例を完成 | FE担当: invite/accept画面・unit/integration。検証担当: 実BE/PGのIAM02/03/05/06/10 E2Eとrunn全体回帰。文書担当: local起動・操作・未合意範囲を更新 |

PR1はBEだけ先にBasicを廃止しない。OpenAPI・生成・BE・FE・runn・既存E2Eの認証変更を同じPRで原子的に揃え、各merge後に全CIをgreenに保つ。
PR2も公開する新APIは正本・生成・runn・BE検証まで同時に追加する。PR3まで既存FE/BEの単体・統合・E2Eを一時停止しない。
Contract/生成ファイルは一人が所有し、BE/FE担当は直接並行編集せず変更要求を集約する。migration番号と共有audit変更もAccess担当一人が管理する。
各PRは直前のmerge SHAへ追従し、別contextレビュー・最新SHAの全CI・通常gateを通す。Issue #2は3本と全受入例が揃ってから完了とする。

## まだ決めていない範囲

追加Administratorの招待とemail loginはユーザー回答で確定済み。この点の再質問は不要。
password reset、account recovery、email変更、メンバー失効/退会、最後のadmin保護は未合意。必須範囲へ黙って追加せず、必要性を質問して後続Issueへ分ける。
単一BEのsessionは再起動で失効する。複数instance・永続session・外部identity providerは今回のローカル範囲に含めない。

## 初回縦断の実装境界（履歴）

最初のPRはDBユーザー、local明示seed、cookie session、匿名CSRFとlogin/logout、role/tenantのDB再読込、顧客操作の監査actor UUIDまで。招待・回復・role管理は後続PRで、未実装を完了扱いにしない。login入力は空でないことと上限を検査し、作成時の最小長をloginへ誤用しない。local以外はfail closed、auth-testはseedなしのDB fixture専用。
