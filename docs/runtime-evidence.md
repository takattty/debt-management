# Cloud validation and resume record

対象: Issue #1。日付: 2026-10-29（Asia/Tokyo）。最新SHA/CI/reviewの結果はIssueの最新コメントを正とする。合意した業務全体や基盤全体の完成を意味しない。

## 保存した成果物と順序

旧PR #8は大きすぎるとのユーザー指示で未merge閉鎖。小さな依存PRに分割：#9設計/信頼ゲート、#10 OpenAPI契約、#11 Gradle Kotlin BE、#12 OpenAPI生成FE、#13自己改善、最後に実行基盤。手書き差分とgeneratedのlock/types/wrapperを区別する。

後続は#2認証/招待/監査→#3顧客・請求CSV/債権→#4入金/消込→#5回収→#6貸付元本。#7がGitHub gate/権限の阻害管理。基盤成立前に機能完了と扱わない。

## 実施・Cloud検証済み

- `/workspace` のCloud実行、Macの常時起動不使用。設計/BE/FEの所有ファイルを分けてsub-agent実装。実装会話を継承しないreview contextを別途作成。モデルの変更は実施していない。
- Root/FE/BE AGENTS.md、repo-local Skill、Issue/PR templates、要件R01〜R25、用語、業務例、DDD context境界と代替案/影響、ADR、ロードマップ。
- 技術固定: Cloud JDK `25.0.5.0.3` Oracle（事前setup）、Node `24.19.0`、npm `11.9.0`、Gradle `9.1.0` Kotlin DSL＋公式SHA256 wrapper＋STRICT dependency lock、Boot `4.0.2`、PostgreSQL `18.0`、Playwright `1.56.1`、Cloud Chromium `154.0.8037.57`。Docker `28.4.0`、Compose `2.40.3`。
- Maven初期実装はユーザー指示で削除した。現在の検証/build/CIはGradleを使用。
- OpenAPI `3.0.3` が契約源。Generator `7.17.0`でBE interface/DTO、openapi-typescript `7.13.0`でFE型生成。Redocly `2.72.1`で構造・参照・operation検証。生成差分を意図的に作り、checkが非0かつ書換えなしで拒否するテストあり。
- `CHROMIUM_PATH=/usr/bin/chromium scripts/verify.sh` 成功（詳細/最新対象はIssue）。BE OpenAPI validate/generate/compile/Checkstyle/実PG、FE契約/型/lint/unit/build、review判定と失敗学習の単体、headless画面→実API→DB。
- `CHROMIUM_PATH=/usr/bin/chromium scripts/docker-verify.sh` 成功。digest固定Temurin25 JRE/nginx/PG18で顧客登録・永続化・重複・logout・tenant分離の同E2E。
- DBを実際に使用し、H2/APIモックをE2Eの代替にしていない。テスト専用の架空DBを使用。
- root scriptsのポート事前拒否・起動ログ/PID確認を修正。既存8080 listenerがある場合にverifyが非0を返し、旧serverを成功と誤認しないことを再現検証。
- 故意unused変数でCloud lint exit1、除去後passを確認。GitHub品質失敗のmerge拒否とは別の証拠。
- 同原因の別失敗2回で未解決を拒否する学習CLI、event重複排除・再発reopen・実在guard参照と回帰テスト。GitHub403とnpm cache権限の対策を保存。

## GitHubの検証と未達

- GitHub Issue/comment/branch push/PR作成に成功。metadata.admin=trueでもsettings/status書込みの実権限を保証しない。未対応APIを同じ権限で反復しない。
- 初期private/Freeの制約後、ユーザーがpublicへ変更。標準ubuntu GitHub-hosted runnerのみ使用し、larger runnerや有料APIは導入していない。
- ユーザー設定のruleset26124161をread-only APIで確認：active、main、bypassなし、PR必須、source15368のquality/independent-review必須、strict、削除/forcepush禁止。
- 旧head e4ace7bでquality成功/review失敗のmergeをGitHubが405拒否。main不変。詳細 `docs/evidence/merge-gate-probes.json`。
- 初回Actions setup-javaのversion表記不一致で失敗。利用可能な `25.0.1+8.0.LTS`へ修正。#9のe7b6d06ではquality成功、別context review pass/blocking0。
- trusted gateはread-only review relay→default workflow_run、head/default relay blob一致、全openPR再検査、writer直列化。code/mockレビュー合格とGitHub実動作は区別。
- mainにconsumerがない初期循環のため、独立レビュー済み固定SHAだけのbootstrap例外をユーザーに提示。BOOTSTRAP_REVIEW_SHA未承認の間は実行/mergeしない。初期baseとhead pin一致＋明示レビューだけでstatus発行。初回merge後base不一致で再利用不可、変数削除とworkflow除去が後続作業。
- review変更からActions処理までは待ち時間がある。Coordinatorはmerge直前も最新head/reviewsを照合し期待SHAを渡す。GitHub標準承認と同じ原子性を主張しない。
- auto_mergeは初期false。新gateの実イベント/故意失敗時拒否/権限を実証し、受入条件が揃うまで有効化しない。

## 実際に直した失敗

ネットワークは許可されたproxy経路を使用。JDKのsession CAは外部の複製trust store、Gradleのproxy/trust設定はworkspace toolchains内に置き、TLSを無効化せず秘密/設定値をcommitしない。Docker build cacheはread-only homeを避けBUILDX_CONFIGをwork配下へ。umask077由来のjar/assets読取権限はCOPYで明示した。npmの既定home cache問題はproject .npmrcで予防。

独立レビューで発見した旧server誤認、gateのheadコード信頼、壊れた新reviewから古いpass再利用、CSV文書不一致、review再判定/並行writer問題は修正し、対象SHAを更新して再レビューした。個別consumerの後続findingsはPRと最新Issueコメントを参照し、解決前に成功を流用しない。

## 未実施・制約

- Cloud別タスクAPIによる委譲、実際のCloud task停止→再起動、`/goal`、常時稼働Coordinatorは未検証。sub-agentやアプリ再起動と区別する。利用可能なツールに別Cloud task起動/停止APIは見当たらず、使用済みと報告しない。
- Docker対応のため、直接PG fallbackは未実行。`postgres-cloud.sh` は未検証の予備手順。
- 正式認証/招待、CSV/債権/消込/回収/貸付は未完成。dev架空認証は本番利用不可。
- Cloud JDKとDocker/CI Temurinのpatch差、Cloud installed ChromiumとPlaywright固定browserの差を明示し、各経路で検証する。

## 再開手順

1. Issue #1/#7と各PRの最新コメント、head SHA、CI、review対象SHAを確認。`docs/roadmap.md` に従う。
2. `scripts/fetch-main.sh`でremote一致を確認し、Issueが指定する対象branchをcheckoutする。古い原作業treeの未commitを上書きせず、GitHub branch/PRを復元元とする。helperはactive code checkoutの絶対pathから呼び、証拠はread-evidence.pyで所有先を解決する。
3. `source scripts/toolchain-env.sh`、Java25/Node/npm/Gradle wrapper、Cloud network policy/proxy/CAを確認。
4. テスト専用PGを起動し `CHROMIUM_PATH=/usr/bin/chromium scripts/verify.sh`。テストは対象テーブルを初期化するため実データDBで実行しない。必要ならDB_URLで専用DBへ。
5. `LOCAL_SEED_PASSWORD=debt_local_demo_2026 CHROMIUM_PATH=/usr/bin/chromium scripts/docker-verify.sh`。専用の架空データ用Docker projectで成果物を再buildし実E2Eを行う。通常停止でvolumeを削除しない。
6. 仕様/ADR/Issue/base/headを別context Reviewerへ渡す。修正後は新SHAで必要な再検証/再レビュー。
7. 通常gateの最新SHAでquality・独立レビュー・blocking0・受入条件を揃えてからmerge。bootstrapは削除済みで再導入しない。#3〜#6の業務機能へ進む。

生ログはignored work配下。重要結果/阻害/再開はIssueとtracked evidenceへ残し、Cloudの作業領域だけに依存しない。

## 導入後の実証追記

初期説明の未達記述は初期snapshotであり、以後の実結果は以下。PR #9はユーザー固定pin承認後に実bootstrap status/quality成功、head/base/latestreviewを確認し737c01eへmerge。ユーザーがpin変数を削除し、経路はPR #16でb9f48e6へ削除した。通常gateは#16同一SHAで未review→failure、明示review→success、review編集→failure、merge405、再review→successを自動event経路で確認。#18はreviewだけsuccess・qualityだけfailureの状態でquality名を示す405拒否、未merge閉鎖。必須checks/ルールを削除していない。

repo.allow_auto_merge=trueをread確認。#12はGitHub AutoMergeEnabledEvent(UTC15:17:19)を記録し、check待機中auto_merge設定と未mergeを実観測、その後必須成功でUTC15:18:34にGitHubがafd2d916へmergeした。rootが条件未達で強制mergeした事実はない。CodeRabbitはユーザーがuninstallし、新規PRのcheckから消えた。

CIの最終mandatory化とrunnはPR #17、latest472b552のrun40895337491成功でBE13/FE5/runn3books39HTTP/E2E1/metadata-policy2/Python5/生成drift1を実ログ確認、同SHA独立review5699802391がpass。後続でSHAが変わればそのCI/再reviewを別途揃える。#1のCloud別task委譲/実停止再起動は引き続き未実証で、software gate成功と区別する。

## 認証・招待の統合検証（2026-10-29）

- #19/#20でDB email/password・Argon2id・HttpOnly cookie・CSRF・tenant/role再読込を統合。#21/#27/#28でhash-only招待、24h単回、管理者/経理権限、並行受諾と監査rollbackを検証した。
- #29の最新head `4bd35a733abfc2ca0ea19f61d129f8948ca30112` は独立review5702365585、[CI40923806927](https://github.com/takattty/debt-management/actions/runs/40923806927)成功。実ログでJava67、FE31、runn6シナリオ、実E2E5、native BFCache2を確認。履歴復元のリンク再表示は独立probeで発見→同期破棄へ修正→同probeとCI guardで解消した。
- #30の最新head `8f8b2ba597ee60034f82c0987a6d83bca74cbf13` は独立review5702504818、[CI40924778627](https://github.com/takattty/debt-management/actions/runs/40924778627)成功。実ログでJava74、FE31、runn7シナリオ（132HTTP）、実E2E5、native BFCache2、Python7、review-policy3、生成drift1、audit0/生成/Lint/型/buildを確認。独立検査でも74 Java/132HTTPとClock・並行予約・cookie/XFF・IP上限・監査失敗を実証。
- Docker対象main `336971283bf0434cb6698f3110bd4448fc1a06c9` を専用projectでbuildし、実E2E5＋native BFCache2成功。stop→upで顧客6件・User6件・受諾済み招待3件を保持し、再ログインも成功。実行中backend jarのSHA256は今回build成果物と一致した。
- #24/#26の追加review撤回→required failure→実merge405・main不変は[Issue #7](https://github.com/takattty/debt-management/issues/7#issuecomment-6503430700)。probeは未mergeでclose、必須2checksを維持した。
- 自己改善は同原因の再発を新eventとして再openし、原因・対策・検証・guardを更新。cwd起因の取得誤りには明示repo wrapper/証拠owner resolverと回帰テストを追加した。新しいdev guardのPython検証は12件成功、CIも通常verifyのunittest discoveryへ含める。

売掛金の顧客CSV/請求preview・確定・訂正取消、入金・消込・滞留・督促、貸付元本は未完成。CSV codec #23と受動保存core #25は業務縦断の完成ではない。Issue #3のB01〜03（既存顧客CSV、請求番号、金額/期日）の回答待ちを保存し、回答後に同Issueから実装を再開する。Cloud taskの停止後再起動・別task委譲・/goalはアプリのstop/upやsubagentと同一視せず未検証のまま残す。
