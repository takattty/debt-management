# Workspace capacity validation

2026-10-30、Cloud overlay約32GB。完了済みcheckoutに再生成可能な依存物が累積し、別々の3操作でENOSPCとなった。Gradleのassertion成功だけをbuild成功と扱わず、report書込失敗を全体失敗として維持した。

| event | 観測 |
| --- | --- |
| worktree-mkdir-enospc-20261030 | Coordinatorのgit worktree addでdirectory作成失敗 |
| gradle-reports-enospc-20261030 | 独立reviewerの126 assertions通過後、Gradle HTML/problems report書込に失敗しBUILD FAILED |
| e2e-results-enospc-20261030 | FE Playwrightがtest-results mkdirで失敗し、API未実行 |

Coordinatorは所有者と完了を確認した7treeのignored `frontend/node_modules` / `work/npm-cache` だけを、ignore状態とsymlinkでないことを確認して整理した。source・jar・DB・log/証拠は保持した。helperは削除を自動化せず、不足時にこの限定手順を案内する。

## 実装と否定検証

`scripts/check-workspace-capacity.py` は実行先filesystemのavailable bytesを測定し、固定1GiB未満なら終了値1、取得失敗なら2、1GiB以上なら0。閾値のoverrideやskipはない。Cloud wrapperはlog作成前、verifyは開始時・Gradle前・FE install前・E2E前、標準Linux CIもcheckout直後に実行する。git読取や証拠readerには適用していない。

| 検証 | 結果 |
| --- | --- |
| mock free=1GiB / 2GiB | 終了値0、実測値・必要値を表示 |
| mock free=1GiB-1byte | 終了値1、完了treeの再取得可能依存だけにcleanupを限定する案内 |
| mock disk取得失敗 | 終了値2、available=unknownを合格としない |
| 実Cloud wrapper + fake capacity失敗 | mkdir/log作成・verify起動より前に終了値1/2で停止 |
| 実Cloud wrapper + fake verify終了値0/19 | preflight通過後の既存verify終了値とlogを保持 |
| 実verify入口 + fake capacity失敗 | 重い処理より前に停止。通過時の既存E2E_BASE_URL拒否も保持 |
| 単体検証 | 容量guard 6 tests、Python全30 tests成功 |
| 実容量測定 | available=1,679,151,104 bytes (1.56GiB)、required=1,073,741,824 bytesで終了値0。並行作業で値は変わるため各開始前に再測定する |
| bash構文・diff検査 | 成功 |

## 復旧後の実retry

Coordinator共有の実行証拠では、`debt-invoice-cancel-ready` worktree作成が復旧後に成功した。FEは対象 `03da02933279f402a25e1aaf619d44c655567189` でAPI生成・lint・type・unit81・build成功、実backend/PG接続のPlaywright1件も成功した。証拠はFE所有treeの `work/preview-check-fixed.log` と `work/preview-e2e-fixed.log` であり、このtreeの相対pathと混同しない。

独立reviewerも対象 `474676f7adf88c2a16fd9e70c6d0a69c42844241` で復旧後のcheck/bootJarが126 tests・0skip・0failure、runn9 scenarios成功と報告した。所有treeの `work/review-confirm-port-gradle.log` にBUILD SUCCESSFUL、`work/review-confirm-port-runn.log` に9 scenarios/0skipped/0failures、FEのE2E logに1passedがあることをreaderで直接確認した。先の `work/review-confirm-port-report-save-failure.log` も保持している。

新guardのmock否定・実測・実retryを確認してからfailure-loopの原因・予防・検証・guardをresolveへ保存した。業務コード・PG設定・required CI検査を変更していない。

## VFS Compose複製に対する追加改善

同じCloud filesystemで、新たに次の2実行が容量不足となった。以前の3eventsを保持し、同じ失敗を複数agentから報告されても重複計上しない。

| event | 観測 |
| --- | --- |
| docker-vfs-compose-up-enospc-20261030 | native build後1.6GiB、Docker build後1.19GiBあってもVFSでPG/BE/FE containerを作ると0となりPG初期化失敗 |
| collection-fix-review-worktree-enospc-20261030 | 別reviewerの新worktree mkdirもENOSPC |

Coordinatorは停止済みの旧6containerだけを、元log・image情報・volume mapの退避とhash照合後に除去し、named DB volume2を保持して3.23GiBへ復旧した。guard作者はcontainerの起動・削除・再作成を実施していない。

今回の旧auth/stackの停止済み6container層の除去は、rootが所有・停止・原log/image ID/volume mapの退避を確認した個別の手動復旧で、named volumesは保持した。一般のcleanup規則の拡張や自動cleanupを認めるものではない。

`build-local.sh`の各重いphaseは共通1GiB検査を使う。`docker-verify.sh --cloud-local`はmanaged Cloudの明示local daemonだけを対象に、buildとupを分けて直前に測定する。通常Mac/Desktop/remoteのselected contextは変更しない。個人のDocker認証/configファイルを独自に読み取らず、CLIのregistry/proxy設定とTLSを維持する。

Cloud見積りはdaemonrootとworkspaceの実空きを測定する。VFSでは新規またはimage/configが変わったcontainerごとのuncompressed image.Size全量を加算する。既存容器でもPGが健康な稼働状態でなければ、PG image.Size以上（最低256MiB）の初期化/起動余裕を別に確保する。buildは実base image.Size・COPY元の実bytes・filesystem変更layer数と一時working copy・context/tar metadataから上限を計算し、後続app containerの予算も残す。最後に1GiB reserveを加える。COW driverにVFS全量複製を誤加算せず、unknown driver/image/FS、未対応Dockerfile、取得失敗は拒否する。容量は予約されないため他heavy workerを止める。

| 検証 | 結果 |
| --- | --- |
| fake CLI/FS | 0space/unknownで作成なし、同じ2GiBでVFS拒否・overlay2通過、現container再利用、config/image変更再作成、PG余裕、payload増加によるbuild予算増加、helper/Compose owner分離を確認 |
| real shell entry + fake tools | native guardでGradle/npm前停止、Cloud build/up拒否で次のmutationへ進まない、通常Desktop context保持・Cloud endpoint限定を確認 |
| root既存projectのread-only plan | 実vfs。available3441127424B、required1578107736B＝reserve1073741824＋PG455836883＋frontend clone48529029。backend/PGは既存image/config一致。Docker build/up/startは実施せずroot管理で後続検証 |

read-only planは `check-docker-capacity.py --cloud-local --worktree ROOT_PROJECT up`。helperは自分のcheckoutを使い、Compose sourceの所有先を明示する。raw config/inspectに含まれ得る環境値は表示せず、安全な予算内訳だけを出力する。既存のworkspace/CI/認証phase/fixture/processownership guardsは保持する。

同じ既存projectのread-only build planも、実buildを始めず検査した。build/context/layer1536753779B、後続app clone398156569B、PG455836883B、reserve1073741824Bの合計3464489055Bに対しavailable3435003904Bのため終了値1で拒否した。guardの拒否は追加の偶発ENOSPC eventではない。rootがrebuildを選ぶ場合は余裕確保後に再測定し、既存imageのupだけを選ぶ場合も直前のup planで判定する。
