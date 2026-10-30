# 手動名義対応のAPI検証

2026-10-30、B05/Q02・EX-M06/M13、R16/R18/R19/R21、ADR-0001/0002。起点は `bb2b3771f142dc5689c0a6b450419304aa67b8ca`。これは入金日付修正 `ad786c9` に既存payment runnのfixture探索修正だけを追加したbaseであり、その変更は本sliceの差分に含めていない。

## 実装範囲

ADMIN/ACCOUNTANTの名義対応登録、active対応のbounded一覧、version/reason付き無効化、無効化後も原対応を再取得する最小詳細を実装した。Java本番は新規3file・167行と権限設定1行、V7はpayer_alias専用のschema/制約/履歴保護。OpenAPIを正本にJava interface/DTOをbuild生成し、TypeScript型257行を再生成した。生成物を手編集していない。

名義はCSVと同じ `PaymentValues.text(value, 200)` を呼ぶ。前後空白以外のNFC・case・全半角・カナ変換を行わず、同名複数顧客は許可する。同tenant同名同customerのactive pairだけをpartial unique indexで拒否する。無効化後の再登録は新IDとし、原行・登録者/時刻・無効化者/時刻/理由・監査を保持する。

## 実行結果

| 検証 | 結果・証拠 |
| --- | --- |
| BE `openApiValidate check bootJar` | 全165 tests・0failure/0error/0skip。work/alias-be.log、backend/build/test-results/test |
| 新規domain/実PG/HTTP | 8 tests。名義共通化・Unicode区別・200code points、複数顧客、activepair重複、同時登録/無効化の一方成功、監査失敗時の登録/無効化rollback、tenant顧客/actor FK、原対応の変更/削除拒否、版/CSRF/未知field/越境を検証 |
| 実HTTP runn | alias bookの28steps成功後、既存を含む全11 scenarios・0skip/0failure。work/alias-runn.log、work/alias-runn-all.log。OpenAPI応答検証を維持 |
| FE契約/型/Lint | generate:api・check:api・typecheck・lint成功。API drift否定probe1件成功、work/alias-api-drift.log |
| 差分・運用記録 | git diff --check、failure-loop check成功 |
| 実行分離 | PG5432の専用DB debt_payer_alias、API8096、pool最大4。他workerのDB/runtimeに変更なし |

runnの複数runner共有cookie jarで2件の403を観測したため、全28requestsを `useCookie: false` とrole別の明示Cookieへ固定した。実bookを解析して全requestのsession方針を検査し、実HTTPの成功後も原因・予防・検証・guardをfailure-loopへ保存した。CSRF条件を弱めていない。

FE型生成はlockが一致した既存datefix checkoutのnode_modulesを読取symlinkで再利用し、完了後にsymlinkだけをunlinkした。依存実体を変更・削除していない。Gradle/socket/Node child processはmanaged sandboxの通常制限では起動できず、許可されたnetwork付き実行環境で実施した。API driftの意図的stale出力拒否を含め、実行制限による失敗を成功扱いしていない。

## 未実施と境界

名義対応のUI・browser E2E・候補提示・ledger・残高更新・消込は実装していない。reconciliation有効化とInvoiceMutationEligibilityのguardを変更していない。B06/B07は未回答のまま維持する。独立レビューとGitHub CIはこの実装作業では未実施。

開始前は空き2.24GiB、HTTP検証前は2.95GiB、全検証後の再測定では他作業の増分により0.14GiBとなり容量guardが停止した。その後の重い処理は行わず、無断cleanupも行っていない。既存失敗log・DB・jar・証拠を保持する。

## 既存データを保持した受入テストの訂正

独立reviewerは既存Beta対応を正規APIで登録し、全11冊中のalias bookだけが「Beta一覧の全件数=0」という仮定で失敗することを確認した。実APIのtenant分離は正常だった。受入テストを、自bookのIDで検証する形へ訂正した。

Betaにも顧客と名義対応を正規APIで作り、ID keysetで各tenantの全pageを辿る。Betaでは自fixtureの存在と自bookのAlpha対応3IDの不在、Alphaでは有効な自fixture2件の存在とBeta自fixtureの不在を確認する。既存の正当なAlpha/Beta対応は件数に関わらず許容し、DB初期化・既存record削除を行わない。loop内のtestはretry待機ではなく各pageの不漏洩を即座に検査するため意図的に維持している。

| 検証 | 結果・証拠 |
| --- | --- |
| 対象環境 | reviewerの377実jar・API8094/pool4・専用DB review_payer_aliasを維持。本番/API/契約/DB変更なし |
| 既存原本 | Alpha/Beta両tenantの既存alias19行と関連audit22行を、実行前の固定ID集合でsnapshot |
| 全11冊1回目 | 0skipped/0failures。work/alias-runn-existing-first.log |
| 全11冊2回目 | 同DBの新規fixtureも残したまま0skipped/0failures。work/alias-runn-existing-second.log |
| 既存原本保持 | 前・1回目後・2回目後の固定subsetは同一。SHA256 `652400cbbe543c740ec45d6b45d386771f33e95c966908cd3cc3f19d69dc3716`。work/alias-fixture-repeat.log と3つのsnapshot JSON |
| learning | 旧2events/resolutionと今回の3event目をwork/alias-fixture-learning-entry.jsonへopenのまま保存。旧guardのFEfileはこのbaseに未導入なので歴史を書き換えず、mainでのkey unionと正式resolveをCoordinatorへ引き継ぐ |

この訂正ではrunnと検証文書だけを変更し、BE再build・型再生成・UI検証を実施していない。元377の本番コードは同一で、独立再レビューは別途必要。
