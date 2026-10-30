# Bounded source output

旧準備版 `8b4135462793198faf696de2f566ae346df2dfad` の記録を保持し、main `f1d91ac57ba06b195e3c8fd8aae17b02c7e3f133` へguardを移植した。固定版 `141fb384bdca57ae379d017a94c37858dbacd573` はarchive branchへ保持する。以下の旧版・固定版の実検証と、末尾の最終追補・再利用した結果を区別する。

旧版の2026-10-30の起点baseは `2d02e8bab0ecf9f76ff7796f004cbc4b2d1ba860`、最終baseは `d367c448d98345ad724caa990eeab937b09e1214`。当時の20keys/61eventsは旧版の件数である。artifact readerの16KiB制限だけでは、複数sourceを直接まとめてtoolへ出す操作を制限できなかった。

## 実観測と既存履歴

既存`tool-output-truncation`の2eventsはGit DB metadata/Docker archiveの巨大JSON出力が切断されJSON.parseに失敗したもの。この履歴とresolutionを保持し、独立reviewerから受領した次の2eventsだけをunique IDでunionして計4eventsとする。

| event | reviewerの実metadata |
| --- | --- |
| ledger-review-multifile-source-truncated-20261030 | tool chunk `368cbc`、max_output_tokens 5600、observed original 6263 tokens。Java・要件・設計・READMEの直接まとめ読込でsource textが切断 |
| ledger-review-source-budget-still-short-20261030 | tool chunk `b7d63d`、max_output_tokens 3200、observed original 3542 tokens。別の小さいbatchでも表示予算不足。mutation body・test・contract diff・fixtureを含む |

提供元は`/workspace/review-ledger-public-read-guard/work/source-output-command-metadata.json`と同treeの`work/source-output-learning-fragment.json`。reviewerはJSON parseを試みず、切断payloadを読了や完成結果として扱っていない。最初の2JSON事故と、新しい2source表示切断を区別する。今回担当者が元の切断を再誘発したとは主張しない。否定テスト・既存記録の再読・guardによる意図的拒否は新eventに数えない。

## guard

- `read-source.py`はheader・行番号・catalog/candidate pathsを含むUTF-8 stdoutを8192byte以下に制限する。超過はexit2・stdout空とし、queryか`--start/--end`を狭める案内をstderrへ返す。部分headerや先頭行を成功payloadとして出さない。上限を解除するoptionはない。
- owner root、tracked source、非symlink、secret filename、dot/work/build除外のcatalogを維持する。一意でない/不一致のqueryは安全な候補pathのみで、推測したsource本文は読まない。既存の小さい読取・範囲・候補の終了値は維持する。Markdown/YAMLや鍵・設定fileをsource allowlistへ追加しない。
- 1helperの8KiB以下だけで、複数helperや直接catの合算が安全とは判断しない。原則1helper表示を1tool callへ分け、toolのmax_output_tokensを十分確保する。byte数とtoken数は同じ単位ではない。上限や予算が不明なら範囲をさらに小さくする。
- source textでも、toolが完了したか、exit code、truncationなしを先に確認する。JSON parseする応答でも同じ契約検査を先行し、切断prefixを取り除いて欠けたpayloadを成功扱いしない。失敗時は観測済みpathの小さい範囲へ分割する。
- 古いprep/旧baseの操作でも、最初にCoordinator指定の最新main operator checkout/SHAのAGENTS・learningを確認する。古いAGENTSだけでは新guardを持たない。helperはoperator側の絶対pathで呼び、実catalog/読取対象は`--worktree`で固定する。

例（実際には先のcallでcatalogを観測してからそのpathを使う）:

```sh
python3 /absolute/operator/scripts/read-source.py InvoiceMutationService --list --worktree /absolute/target
# 次のtool call。観測済みpathだけを指定する。
python3 /absolute/operator/scripts/read-source.py backend/src/main/java/com/example/debt/invoice/InvoiceMutationService.java \
  --worktree /absolute/target --start 1 --end 10
```

## 実再読と回帰

| 検証 | 結果 / 改善treeのwork証拠 |
| --- | --- |
| reader回帰 | 14件成功。単一巨大行・日本語byte・8192/8193byte境界・header/行番号加算・多数catalog/candidate・巨大secret拒否・通常範囲・既存owner/tracked/symlink等を検査 |
| 全Python | 起点で58件成功（`work/python-tests.log`）、最新baseで68件成功（`work/python-tests-final.log`）。helper/testはrebase前後byte同一 |
| 切断batchのcode | 観測済みcatalogから5files/277行を10行ずつ再読し、欠落/重複なく元の全行と一致。最大1,909byte/応答。`work/source-reread-proof.json` / `work/source-reread/` |
| mutation本文 | 実`InvoiceMutationService.java`全88行を9範囲で再読し全行復元。旧source SHAは不変 |
| 同batchのMarkdown | 明示catalogした3docsを読取専用snapshotへ保存し、evidence readerの4096byte chunksで全byteを照合。source allowlistの拡大ではない |
| contract差分 | 既存artifact `work/contract-reviewed.diff`の6478byteを2chunksで全復元、SHA `75c34d1dbd9db6c3ec90154f2554e5f07fa41f7fa0d98d938f1920d661a591cc`一致、最大応答5,735byte |
| 保持 | code/docs/contract原本を変更せず、全byte/hashが再読前後一致。既存2events/resolutionを保持し新2eventsのみ追加 |

再読対象のownerは`/workspace/review-ledger-public-read-guard`、観測HEADは`d54aa4eb268e96655ddfe2b08418bbc76eb7def3`。source helperの結果はfileへ保存して全行を検査し、巨大batch本文を再びtool stdoutへ流していない。実行scriptは`work/prove-source-reread.py`。

業務source/doc・JAR・DB・既存証拠は保持。変更はoperator helper/test/guard/learningだけで、BE/FE/npm/runtime/Composeは実行しない。GitHub書込・独立review・必須CIは作者の実証へ含めない。

## 固定版141fb38の移植と再発

mainの全key・event・resolutionを元の順と内容で保持し、`tool-output-truncation`へ旧準備版の2eventsとevent_count=4のresolutionをそのままunionした。その後、以下のroot 1件・publisher 3件を追加し、同keyは計8eventsとした。旧版・mainのresolutionは削除せず、今回の実検証後に8eventsを対象とする改善を追記する。意図した拒否probeや同じ証拠の再読は新eventに数えない。

| event | 実観測・保存先 |
| --- | --- |
| root-resume-environment-source-output-truncation-20261030-1133 | environment_statusのallowed-host全文とAGENTS等を合算しoriginal 10,905 tokensで切断。原記録は `/workspace/debt-ar-final-acceptance/work/resume-output-truncation-20261030-1133.md` |
| serial-delivery-learning-output-truncation-20261030 | AGENTS・skill・learning・catalogの合算を切断。chunk `29e104` |
| serial-delivery-git-diff-output-truncation-20261030 | 長いJSX行を含むraw git diffを表示して切断。chunk `92cf2b` |
| serial-delivery-multi-issue-token-budget-truncation-20261030 | Issue本文を各1,800文字にしても合計3,168 tokensがmax2,800を超えた。chunk `e65cec`。完全なGitHub応答は元ownerのworkへ保存済み |

publisherの正本は `/workspace/debt-publish-payment-preview/work/serial-delivery-output-incidents.json`（SHA256 `634ee56fc1fefd5850793dbb77042b810a24ce6a109f4ced2ffb59145e7f3a28`）。各切断応答を完全なJSONとしてparseせず、欠けたsourceを読了扱いしていない。

`source-file-name-assumption`にも別の1件 `serial-delivery-api-errors-blob-guessed-20261030` を追加した。未catalogの `frontend/src/api-errors.ts` を `git rev-parse` に渡し、path不存在でexit128となった実観測である。正本は同ownerの `work/serial-delivery-source-incident.json`。以後は対象SHAのchanged-file catalogから得た実pathだけを使い、有効なblobと未変更treeを照合する。

`gh pr edit`のProjects classic GraphQLエラーは別caseであり、source/output ledgerへ混在させない。提供された `work/serial-delivery-gh-edit-incident.json` はmetadata artifactとして保存した。今回の担当者はGitHub操作を行っていない。

## 141fb38で実施した運用guardと検証

- 原則1 bounded readerを1 tool callで表示し、`max_output_tokens` は8192以上を確保する。文字数・byte数・token数を混同せず、stdout総UTF-8 byte数とwrapperの余裕を確認する。複数の文書やsourceを合算して予算を使い切らない。
- raw diff、Issue/GitHub応答はworkへ保存し、stdoutはID・URL・byte数・hash等の小さいscalar metadataだけにする。環境statusはready/enforced等のflagsだけを表示し、allowed-host一覧全文をsourceと合算しない。
- Git objectのpathもcatalog規則の対象とする。対象SHAの `git ls-tree` / `git diff --name-only` を先のcallで観測し、次callで実出力のpathだけをblob/hash照合へ渡す。最新operatorのguardを古い準備treeでも使う。

| 今回の検証 | 結果・work証拠 |
| --- | --- |
| readerの回帰 | 既存6件と旧準備版の追加8件を再実行。通常読取・除外・8192/8193byte境界・巨大行/日本語・catalog/candidateの原子的拒否を維持。結果は `work/source-unit.log` |
| 実sourceの過大出力 | mainの `frontend/src/App.tsx` 全範囲でexit2・stdout 0byte。原sourceのbyte/hashを保持。`work/oversized-source-proof.json` |
| 小分けした完全復元 | 旧版と同じ実source5files/277行を10行ずつ再読し全行一致、最大1,909byte/応答。Markdown3件もsnapshot経由で全byte一致。`work/source-reread-proof.json` |
| contract artifact | 実pathは `work/contract-reviewed.diff`。6,478byteを2chunksで全復元し、SHA `75c34d1dbd9db6c3ec90154f2554e5f07fa41f7fa0d98d938f1920d661a591cc` 一致。READMEは別の2,513byte文書であり混同しない。原proofのpath訂正正本は `/workspace/debt-source-output-guard/work/source-proof-path-correction.json` |
| Git blob/path proof | PR71のhead `7664033` のchanged-file catalogを先callで観測し、次callで全9pathのblob一致とbackend/contracts/scriptsのtree不変を実検証。`work/blob-catalog-proof.json` |
| learning保持 | main全keyと各event/resolutionのprefix、旧準備版の2追加eventsと4event resolutionの完全一致を検査。`work/learning-prefix-proof.json` |
| 全Python・failure loop | 同じ移植treeでreader14件・全Python71件が成功し、実ledgerのcheckも成功。`work/source-unit.log` / `work/python-tests.log` / `work/failure-loop.log` |

現在の証拠ownerは `/workspace/debt-source-output-guard-current`。旧proof・原source・JAR・DB・ログを保持し、今回も重いFE/BE検証やDocker・GitHub操作は実施しない。独立レビュー・CI・mergeは後続である。

## 最終追補の実観測と保持

独立reviewerの正本 `/workspace/review-source-output-guard-current/work/reviewer-output-events.json`（1,417byte、SHA256 `516ae461b60c5347d58207e615877c5a417b364d466be2d379bce7a65be4879b`）から、実truncation `compose-audit-worktree-list-output-truncated-20261030` の1件だけを追加した。unfiltered worktree一覧がchunk `02cc4b`、original 8,981 tokens、max5,000で切断された。完全な応答としてparseしていない。

同正本の `compose-audit-environment-full-output-nearmiss-20261030` は環境status全文を表示したguard違反だが、応答はcompleteで切断の観測はない。near missとして文書・artifactへ保持し、truncation eventには加算しない。この1件を追加した時点では84events・出力切断9eventsとなり、実recipe検証後の9event resolutionも履歴へ保持する。

続いて過去実行の計上漏れ6件を遅延記録した。publisherの既報3件と同じcallではない外側応答2件と、先行ガイドtaskで作者自身が観測した4件である。後者4件はsourceguardの予防策適用後に新しく4回繰り返したものではない。外側応答とそのnested chunkは一つの実行として1件だけ数え、同じchunkを重複計上しない。

| 遅延記録event | 実観測と一意な実行 |
| --- | --- |
| serial-delivery-initial-tool-catalog-wrapper-truncation-20261030 | publisherの外側応答26,960 tokens。ALL_TOOLS全inventory等の合算、関連nested chunk `16ecaa`。既報3件とは別call |
| serial-delivery-initial-environment-status-wrapper-truncation-20261030 | publisherの別の外側応答10,898 tokens。statusのcontent/structuredContent重複全文等の合算 |
| ar-guide-initial-environment-source-wrapper-truncation-20261030 | 先行ガイドtaskの外側応答12,801 tokens。環境statusとsource等の合算、関連nested chunk `93b983`を別加算しない |
| ar-guide-ledger-docs-wrapper-truncation-20261030 | 同taskの別の外側応答10,291 tokens。learning・guide・README等の合算、関連nested chunk `39938d`を別加算しない |
| ar-guide-payment-source-truncation-20261030 | 同taskのchunk `b677f5`。3,681 tokensがmax3,500を超えた |
| ar-guide-allocation-source-truncation-20261030 | 同taskのchunk `bfec52`。2,315 tokensがmax2,200を超えた |

publisherの更新正本は2,713byte、SHA256 `ebf08466a00539f96b67f48841bd21c00e47f75ec4e5fbccf556144b08c7c398`で、既報3件のprefixが完全一致する。作者の過去4件はこの会話の直接観測を `work/guide-author-output-incidents.json` に保存した。今回把握済みの範囲をここで固定し、履歴全体の追加探索は行わない。mainの29keys/76eventsと固定版の83eventsをprefixとして保持し、最終29keys/90events、出力切断は15eventsとする。元のresolution counts 2・4・8・9を保持し、最終15eventsのresolutionを追記する。

最終追補はlearning・文書だけでcode/testを変更しない。固定版で成功したreader14件・全Python71件はsource同一性を照合して再利用し、再実行した結果とは数えない。最終headで追加する検証は、全main/frozen履歴prefix、実1件と過去6件の一意性、near miss除外、実failure-loop check、recipeの動作と7節PR本文であり、証拠は `work/final-prefix-proof.json` / `work/final-followup-proof.json` に保存する。

## 最初のdiscoveryとscalar表示

指定されたoperator checkoutのAGENTSとlearningを小さい範囲で確認してから、最初のworktree/catalog/GitHub discoveryへ進む。tool inventoryも必要なnameでfilterし、全inventoryやdescription全文を表示しない。callごとに「bounded reader 1個」か「scalar metadataだけ」かを先に決める。`max_output_tokens >= 8192` 単独を安全の根拠にせず、sourceの8KiB byte cap・1reader1call・metadata表示を組み合わせる。

環境toolの戻り値はcaptureし、全文やallowed-host配列を表示しない。

```js
const r = await tools.mcp__codex_apps__cloud_environment_environment_status({});
const s = r.structuredContent;
text({
  observations_current: s?.observations_current,
  phase: s?.observed_phase,
  networkState: s?.network_policy?.state,
});
```

worktree一覧は先にartifactへ保存する。tool stdoutへ出すのは保存先とサイズ・hashだけにする。以下はoperator checkoutをcwdにして実行する。

```sh
mkdir -p work
git worktree list --porcelain > work/worktree-catalog.txt
python3 - <<'PY'
from pathlib import Path
import hashlib, json
p = Path('work/worktree-catalog.txt')
b = p.read_bytes()
print(json.dumps({'path': str(p), 'bytes': len(b),
                  'sha256': hashlib.sha256(b).hexdigest()}))
PY
```

GitHub本文も、観測済みの `scripts/gh-repo.sh` でIssue/PR応答をworkへ保存する。次はread-only取得の例であり、今回の追補ではGitHubへ実行していない。

```sh
scripts/gh-repo.sh takattty/debt-management issue view 1 --json number,url,body > work/issue-1.json
python3 - <<'PY'
from pathlib import Path
import hashlib, json
p = Path('work/issue-1.json')
b = p.read_bytes()
s = json.loads(b)
print(json.dumps({'path': str(p), 'id': s['number'], 'url': s['url'],
                  'bytes': len(b), 'sha256': hashlib.sha256(b).hexdigest()}))
PY
```

`text(r)`、raw body、全catalogを直接表示しない。内容確認は次の別callで所有treeを固定したbounded readerへ渡す。
