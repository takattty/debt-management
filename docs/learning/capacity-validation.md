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
