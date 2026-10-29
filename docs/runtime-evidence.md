# Cloud validation and resume record

対象: Issue #1、branch `bootstrap/issue-1`。記録日: 2026-10-29（Asia/Tokyo）。この文書は機能完成の証明ではなく、実施範囲と未達条件を保存する。

## 実施・検証済み

- Cloud executor `/workspace/debt-management`、初期base `87b2c64`。ユーザーMac不使用。
- 別担当で設計、backend、frontendを実装し、所有範囲を固定。利用可能なsub-agentを実使用。モデル切替は使用していない。
- root/FE/BE AGENTS.md、repo-local Skill、Issue/PR templates、R01〜R25要件、用語、業務例、context境界、ADR、ロードマップを保存。
- 後続Issue #2〜#7を依存関係と受入条件付きで作成。
- ユーザー合意: 経理担当者の単独確定、管理者のみ招待。貸付元本は追加貸付/返済/取消を含み利息対象外。
- Cloud Java `25.0.5.0.3` (Oracle、環境の事前setup)、Node `24.19.0`、npm `11.9.0`、Maven `3.9.11` (wrapper SHA256)、Spring Boot `4.0.2`、PostgreSQL `18.0`、Chromium `154.0.8037.57`、Playwright `1.56.1`。Docker `28.4.0`、Compose `2.40.3`、Buildx `0.31.0`。
- Docker daemonとPostgreSQLコンテナ起動成功。CloudにDockerがあるため直接PG fallbackは実行していない。
- `CHROMIUM_PATH=/usr/bin/chromium scripts/verify.sh` 成功。BE9 tests (domain2、API/実PG6、非dev拒否1)、FE4 tests、lint/type/build、E2E1 scenario。
- BE: 顧客登録/一覧/更新・tenant内code一意・tenant偽装無視・楽観lock/同時更新・監査同一transaction・CSRF/logout。FE→実API→DB: 登録、reload永続化、重複409/1行のみ、logout後401、beta tenantの不可視と同一code登録。
- JDK25のtrust storeへsession CAを複製storeに追加、Mavenへ既存proxyを明示。TLS検証は維持。設定/証明書/秘密値はrepoへ追加していない。
- GitHub read/write (Issue/comment/create) 成功。metadataはadmin/pushを返すが、設定APIの実権限を保証しない。
- 初期private/GitHub FreeのrulesetはPro要求403。ユーザーの明示公開承認後、ユーザーがpublicへ変更。最新metadataのvisibility=publicを確認。標準ubuntu GitHub-hosted runnerのみ利用し、larger/self-hosted runnerや有料APIは使用しない。

- `BUILDX_CONFIG=$PWD/work/buildx CHROMIUM_PATH=/usr/bin/chromium scripts/docker-verify.sh` 成功。digest固定のJava25 JRE + nginx + PostgreSQL18のComposeを起動し、同じ画面→DB E2Eが成功。
- 故意のunused variableを一時作成してFE lint exit=1を確認し、除去後lint成功。これはCloud内の否定検証であり、GitHub merge拒否の証拠ではない。

## 失敗と復旧

- execの初期sandboxはnetwork socketを拒否。許可されたnetworkを付与した同じproxy経路で復旧。
- 初期Docker Compose buildは `/home/agent/.docker/buildx` read-onlyで失敗。`BUILDX_CONFIG=$PWD/work/buildx`へ非秘密cacheを移し、DOCKER_CONFIG/registry認証を保持。
- Docker非root JavaがCloudのumask077由来jarを読めず起動失敗。COPY権限を明示し、FE asset権限も明示してDocker E2Eで復旧確認。
- public変更APIは403。ユーザー自身のUI操作によって公開状態へ変更された。
- branch protection readとActions permissions/billingは403 integration不足。保護変更案は自動承認レビューでも拒否され、実行されていない。設定・merge gateの実証が未完了である間mergeしない。

## 未実施・未達

- GitHub Actions結果、PR作成、独立review、故意失敗によるCI/review/merge拒否は、以後のIssueコメントと証拠を正とする。この時点では未検証。
- auto_mergeは初期false。強制gateが未成立の間は有効化しない。
- Cloud別タスクへの委譲、実停止→再起動、`/goal`、常時稼働Coordinatorは未検証。同一セッションsub-agentとは区別。
- 正式認証/招待、請求・入金CSV/消込/回収、貸付元本は未実装。dev-only架空認証の縦断基盤であり、本番利用可能ではない。
- Cloud JDK patchとDocker runtime Temurin25.0.1+8の差を明示。class target25で検証する。ブラウザはCloudではinstalled Chromium、CIではPlaywright固定browserを使用する。

## 再開

1. Issue #1 と #7 の最新コメント、PR head/CI/reviewのSHAを確認する。
2. `git fetch origin && git switch bootstrap/issue-1 && git pull --ff-only`。未commitがあれば上書きしない。
3. `source scripts/toolchain-env.sh`。Java25、Node/npm固定版、Maven wrapperを確認。環境のproxy/CAとネットワークpolicyを確認する。
4. 専用架空PostgreSQLを起動し `CHROMIUM_PATH=/usr/bin/chromium scripts/verify.sh`。テストは顧客/監査テーブルを初期化するため実データDBでは実行しない。
5. `BUILDX_CONFIG=$PWD/work/buildx CHROMIUM_PATH=/usr/bin/chromium scripts/docker-verify.sh` で成果物のDocker縦断を確認。
6. 仕様/ADR/Issue/base/head SHAを、実装会話を継承しないreview contextへ渡す。修正後は新SHAで必要な全検証と再review。
7. #7でGitHub強制条件と故意失敗拒否を確認。#1受入条件を満たした場合のみmergeし、#2→#3→#4→#5→#6へ進む。未成立を完成扱いにしない。

生ログ: ignored `work/verify.log`, `work/backend-build.log`, `work/docker-verify.log`、surefire/playwright reports。重要結果はrepositoryの証拠/Issueへ保存し、Cloudの作業領域だけに依存しない。
