# Playwright status feedback selector validation

`playwright-status-selector-ambiguity` は次の別の実行3件を同根として記録する。意図したlint/DOMの否定fixtureは再発eventに数えない。

| event | 実観測と出所 |
| --- | --- |
| pr47-correction-status-strict-831-20261030 | PR47統合時831の訂正成功assertが、訂正successと一覧loadingの2つのrole=statusに一致した。開始時はrootの引継ぎsummaryを起点とし、その後作者も旧root correction checkoutのwork/independent-e2e-831-failed.log（6006B・70行）をbounded readerで全件直接照合。13件中12成功・1失敗、元assertはtoContainText「請求を訂正しました」。当時はhasTextで局所修正した |
| allocation-e2e-recovery-status-scope-20261030 | settlement FE full-suite0833の回復直後assertが候補と一覧loadingの2statusへstrict競合。実APIの同一payload再POSTは200で確定済み。`work/settlement-fe-20261030T0833-e2e.log` を保存し、元の成功文言を保って所有region+helperへ移行した |
| guide-cancellation-status-strict-20261030 | guideの取消assertが、残っていた訂正successと一覧loadingの2statusに一致した。review-issue3-guide-compose checkoutのwork/guide-browser-initial-failed.logとwork/user-guide-initial-source.ts.txtを直接照合。取消自体はcommit済みで、read-only resumeで結果を確認し、再POSTはしなかった |

原因は共有ページに複数statusが共存するのに、roleだけで単一要素と仮定したこと。元の成功文言や状態assertを弱めず、`statusFeedback(Page | Locator, string | RegExp)` で期待文言をhasTextに使って絞る。元のexpectは維持する。同じ文言が複数なら所有regionをscopeにし、`.first()`等で1件へ押し込まない。

tracked E2Eの直接status判定を共通helperへ移し、ESLintのe2e-only AST制限でhelper以外の直書きを拒否する。既にfilter済みだった正例も統一する。必須lint経路には直書き・filter付き直書き・computed呼出し・静的templateのknownREDと、Page/region helperの許可fixtureを置く。product/unit/nativeのselectorやAPIは変更しない。私有work probeにもFE AGENTSとdelivery-loopの同じ規約を適用する。

guide初回のbefore比較はメモリ保持のみだったが、rootの別途保存済みDB7table before snapshotによって原本保持を確認済みとのroot報告を区別して残す。今後はmetadata担当を含め、mutation前に対象範囲・snapshot/fingerprint・runtime/source識別をwork artifactへ永続保存し、事後比較と結果不明時のGET確認に使う。既存fixtureの削除やblind retryで隠さない。

## 検証状態

独立guardをsettlement FE準備treeへ移し、既存E2Eと入金の新E2Eを同じhelperへ移行した。knownRED lint拒否とPage/regionの許可、型・単体を次に実行し、3eventsへのresolveに静的検証結果と未実施のbrowser範囲を明記する。full browserの結果は別phaseへ保存し、静的合格をE2E合格として扱わない。
