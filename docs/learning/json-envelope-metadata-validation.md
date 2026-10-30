# Bounded metadata for complete JSON envelopes

`content[].text` が短くても、tool結果全体が小さいとは判断しない。任意のtool結果は、表示前に全envelopeをJSON化したUTF-8総量を確認し、raw objectの代わりにbounded metadataだけを表示する。

## nested envelopeの実観測は1件

`publisher-github-job-log-nested-structure-truncation-20261030` は、保存済みGitHub job logの結果を次callで `text(result)` し、外側応答がoriginal54,965tokensで切断された実1件である。最初のfetch/storeとmetadata表示は切断しておらず、外側とnestedを別件として数えない。

top `content[0].text` は17文字だったが、`structuredContent.content` は209,822 codepoints・213,468 UTF-8 bytesだった。JavaScriptのUTF-16 lengthは209,851であり、byte数と区別する。全envelopeは219,859byte、SHA256 `d8a13c54e6a1685c74cf3ef48b4617ee4dd1772b7afcc86487275b428569e3bb`。正本は `/workspace/debt-publish-financial-candidates/work/quality-job-connected-envelope.json` と同ownerの `work/nested-tool-output-observation.json`。

完全な原ログ `work/quality-job-connected.log` は末尾改行込み213,469byteで、nested値そのものとは別のartifactである。原本・envelopeは変更せず、今後もraw表示しない。

## 実行するhelper

先callのcatalogから観測した最新operator checkoutの `scripts/json-metadata.py` を使う。

```sh
python3 /absolute/operator/scripts/json-metadata.py /absolute/owner/work/tool-envelope.json
```

- JSON全入力のUTF-8 byte数を `inputBytes` として表示前に数える。入力上限は16MiBで、超過・不正JSON・不正Unicodeはstdout空で拒否する。
- 全階層の文字列値は本文・prefix・previewを一切出さず、typeとUTF-8 byte数だけを返す。数値・boolean・nullも値を表示しない。
- object/arrayはfield名・件数・indexを辿り、既定depth4/node64、許容depth0〜8/node1〜128で停止する。長いfield名は64 UTF-8 bytesを超えたら名前自体を出さずbyte数だけにする。省略・depth停止を明示し、完全走査とは主張しない。
- 最終metadataをJSON serializationしたUTF-8 bytesを、末尾改行込み4,096byte以下と確認してから一度だけemitする。超過時はnode予算を縮めて再構築し、切断JSONや部分本文は出さない。出力上限を解除するoptionはない。

`inputBytes` は全JSON artifactのbyte数であり、top-levelだけのサイズではない。helperのmetadataにもfield名は含まれるが、元の文字列値は含まれない。1helperを1callで表示し、raw envelopeへfallbackしない。

## functions.execでの保存とmetadata表示

返り値はcaptureして全体をJSON化し、保存する。次の例の `r` は実際のtool返り値とする。V8でTextEncoder/btoaが使えるとは仮定せず、JSON全体のUTF-8量を計測する。

```js
const utf8Bytes = value => encodeURIComponent(value)
  .replace(/%[0-9A-F]{2}|./g, "x").length;
const envelope = JSON.stringify(r);
if (typeof envelope !== "string") throw new Error("JSON envelope unavailable");
const totalBytes = utf8Bytes(envelope);
store("job-envelope-json", envelope);
const metadata = JSON.stringify({
  storedAs: "job-envelope-json", envelopeUtf8Bytes: totalBytes,
});
if (utf8Bytes(metadata) > 4096) throw new Error("Metadata budget exceeded");
text(metadata);
```

storeはfilesystemのJSON artifactと別である。JSON artifactが保存されたら上記helperへ渡す。保存先不明・export未完了の場合も `text(r)` や `text(load(...))` へ戻さない。contentだけ、最初の文字列だけ、UTF-16 lengthだけの検査でraw表示を許可しない。

## 固定版f272時点の検証と保持

| 検証 | 結果・work証拠 |
| --- | --- |
| 対象unit | nested-large・日本語/emoji・幅広/深い構造・node停止・長い名前・巨大数値・不正JSON/Unicode/limits・入力上限・原本不変を含む12件成功。`work/json-metadata-tests.log` |
| 実envelope | 全219,859byteを入力し、nested文字列213,468byteを検出。metadataは658byte、本文ゼロ、original envelope/logのbyte/hash不変。`work/actual-envelope-proof.json` |
| 実fixtureの否定例・保存recipe | invalid node上限はexit2/stdout0。V8の架空nested結果は全130,077byteを表示前に計測してstoreし、59byteのmetadataだけ表示。Pythonのcompact JSON UTF-8 oracleとも一致。`work/open-and-negative-proof.json` / `work/functions-recipe-proof.json` |
| source/guard境界 | 既存のsource reader・evidence readerは変更しない。新helperはJSON metadata専用で、raw source/doc readerの代替として内容を表示しない |
| learning | main31keys/94eventsの全prefixを保持し、実1件だけ追加して31keys/95events・tool-output16件。open16/check exit1を先に保存し、実検証に基づくresolve/check exit0も成功。`work/final-prefix-proof.json` / `work/final-failure-loop.log` |

最初のunitではparserが深さ2,000のJSONを拒否するという環境依存の期待が失敗した。原logを保持し、parserの実装仮定をやめて有効な深いJSONの走査がdepth上限で止まることを検証した。意図した否定probeやこのtest修正を実truncation件数へ加算しない。実ログのraw表示、HTTP、Docker、npm、FE/BE buildは行わない。

## 再開時のpolicy合算表示を別の1件として追補

rootの `root-resume-policy-agents-output-truncated-20261030-dad466` は、network-policy全文・AGENTS・FE AGENTSを同じexec_commandで表示してoriginal6,097tokensがmax5,000を超えた実1件である。実truncation chunkは `dad466`、`8695c6` はこの失敗のchunkではない。recovery `8adf46` ではpolicyをversion/vpn/type/host_countへ絞り、AGENTSを別callで完読した。nested envelope事象とは別実行であり、修正の否定probeは加算しない。

固定版 `f2721fb3104c8e9a432f4bdf2a7eea86ff460d0b` は `archive/json-envelope-metadata-f2721fb` へ保持する。source/testは同一のため12unit成功を再利用し、追補で再実行したとは数えない。元base31keys/94eventsと固定版95eventsを保持し、root事象だけを加えた段階は31keys/96events・tool-output17件だった。最新版main取り込み後の最終件数は末尾に示す。新root観測は `work/root-policy-output-observation.json` に保存する。

再開時は、指定operatorのAGENTS/learningを小さい範囲の単独callで先に確認する。その後でpolicy/environmentをscalarだけ表示する。policyを読む場合は次のwhitelist recipeを単独callで使い、AGENTSやsourceと合算しない。

```sh
python3 - /etc/codex/network-policy.json <<'PY'
from pathlib import Path
import json, sys
p = json.loads(Path(sys.argv[1]).read_bytes())
h = p.get('http_network_policy', {})
h = h if isinstance(h, dict) else {}
rules = h.get('egress_rules', [])
kind = h.get('type')
metadata = {'version': p.get('version') if type(p.get('version')) is int and p['version'] == 1 else None,
            'vpn': p.get('vpn_configured') if type(p.get('vpn_configured')) is bool else None,
            'type': kind if kind in ('restricted', 'unrestricted') else 'unknown',
            'host_count': len(rules) if isinstance(rules, list) else 0}
output = (json.dumps(metadata, separators=(',', ':')) + '\n').encode('utf-8')
if len(output) > 4096: raise ValueError('Metadata budget exceeded')
sys.stdout.buffer.write(output)
PY
```

環境toolの結果は既存の3scalar recipeを使う。予算を増やしたり全文policyを読むことで失敗を再試行せず、1bounded readerか4KiB以下のscalar metadataだけにする。最終prefix・script/test同一性・policy recipe・failure-loop結果を `work/final-followup-proof.json` と `work/final-prefix-proof.json` に保存する。

## helperの入力scopeを先に選ぶ

rootの2つの読込試行は新key `helper-input-scope-assumption` に保存し、出力切断keyへ混在させない。文書読込試行では `docs/learning/README.md` をsource helperへ渡してsuffix除外exit1（`d731df`）、続いてwork専用evidence helperへ渡してexit2（`e08dd4`）となった。別のpackage読込試行では `frontend/package.json` がsource helperのsuffix除外exit1（`43fa14`）。後で実sourceのSUFFIXESを確認し、.md/.jsonが対象外と確定した。2試行の経緯として記録し、正しいreaderへの切替や否定probeを追加eventに数えない。

- `read-source.py` は定義済みsource suffix限定で、.md/.jsonを扱わない。source allowlistを拡大して回避しない。
- `read-evidence.py` の入力はowner指定付きの相対 `work/...` だけである。既観測のdoc/configを渡す汎用readerではない。
- 既観測の非source doc/configは、次の専用bounded読取を単独callで使う。表示前にheader・行番号込みUTF-8 capを確認し、超過時は本文ゼロで範囲を縮める。JSONの内容でなく構造だけ必要なら新metadata helperを使う。

```sh
python3 - /absolute/observed/docs/learning/README.md 1 12 <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
start, end = map(int, sys.argv[2:])
if not 1 <= start <= end: raise ValueError('Invalid line range')
lines = p.read_text(encoding='utf-8').splitlines()
last = min(end, len(lines))
selected = [f'{p}:{start}-{last}']
selected += [f'{n}: {lines[n - 1]}' for n in range(start, last + 1)]
output = ('\n'.join(selected) + '\n').encode('utf-8')
if len(output) > 4096: raise SystemExit('Narrow the range; no content emitted')
sys.stdout.buffer.write(output)
PY
```

latest main `b5c82fc059abaabea5106b5dff95a9e8cdaf8783` の追加済みkeyも含め、main32keys/96eventsの全entry・key順を保持する。nested/rootの実2件と新helper scope keyの2試行を追加した最終状態は33keys/100events、tool-output17件である。f272原本と元2d729のledgerも保存し、helper code/testの12unit結果は同一性を確認して再利用する。

## JSONのcollection型を更新前に検査する

本文を隠すmetadata helperは、呼出側のJSON schemaを保証しない。private publisher stateの`next`はobject、`merged`と`checkpoints`はarrayで、producerとconsumerの両方を確認してから更新する。`next.values()`を使うconsumerへarrayを渡したり、arrayのcheckpointsへ文字列keyで代入したりしない。

```python
if not isinstance(state, dict):
    raise ValueError("state must be an object")
for field, expected in (("next", dict), ("merged", list), ("checkpoints", list)):
    if not isinstance(state.get(field), expected):
        raise ValueError("unexpected collection: " + field)
```

private実行helperにも同検査を導入し、GitHub取得/更新前とstate保存前に実行する。実state1ケースと誤ったcollection型/entry型の5否定fixtureをnetwork0で検査した。merge後のローカルstate保存が失敗した場合、PRのGETでmerged状態を確認し、成功済みmergeを再送せず記録だけ復旧する。2実事象と6fixtureの証拠はpublisherのignored `work/private-state-shape-observations.json` / `work/private-state-shape-guard-proof.json`に保存した。

## Markdown/package JSONはsource専用helperへ渡さない

source catalogの`read-source.py`はMarkdown文書とpackage JSONの読取経路ではない。先に対象ownerで`git ls-files -- docs/path.md`等を観測し、次の実行可能recipeへ絶対ownerと実relative path、狭い行範囲を渡す。sourceファイルはsource専用helper、ignored work証拠は`read-evidence.py`の絶対helper＋明示`--worktree`を使う。候補だけの拒否後に同じhelperでqueryを反復しない。

以下はprivate実行wrapperとbyte同一の標準Pythonコードで、別Cloud contextでもそのまま実行できる。3500 UTF-8 byteにはheaderと末尾改行を含め、超過/非対象はstdout空でexit2、原本は変更しない。Markdown/JSONの成功2件とsource/過大の拒否2件、実Markdownの読取と原本hash不変を検証した。

```sh
python3 - /absolute/target-worktree docs/path.md 1 20 <<'PY'
from pathlib import Path
import subprocess
import sys

CAP = 3500

def render(label, suffix, raw, start, end):
    if suffix not in (".md", ".json"):
        raise ValueError("Use read-source for source files; this route accepts .md/.json")
    if len(raw) > 2 * 1024 * 1024 or start < 1 or end < start:
        raise ValueError("Invalid bounded text input/range")
    selected = "".join(raw.decode("utf-8").splitlines(keepends=True)[start-1:end])
    result = label + "\n" + selected
    if not result.endswith("\n"):
        result += "\n"
    if len(result.encode("utf-8")) > CAP:
        raise ValueError("Text exceeds 3500 UTF-8 bytes; narrow the line range")
    return result

def main():
    root_raw, relative, start, end = sys.argv[1:]
    root = Path(root_raw).resolve()
    rel = Path(relative)
    if rel.is_absolute() or ".." in rel.parts or rel.suffix not in (".md", ".json"):
        raise ValueError("Expected catalogued relative .md/.json path")
    path = (root / rel).resolve()
    if not path.is_relative_to(root):
        raise ValueError("Target must remain in explicit owner worktree")
    subprocess.run(["git", "-C", str(root), "ls-files", "--error-unmatch", "--", relative],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("Input exceeds 2 MiB")
    sys.stdout.write(render(str(path), rel.suffix, path.read_bytes(), int(start), int(end)))

if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.stderr.write(type(error).__name__ + ": bounded text route refused\n")
        raise SystemExit(2)
PY
```

## Hidden-partもsource helperの対象外

2026-10-30の再開で、catalog済み`.github/scripts/review-gate.cjs`をsource専用helperへ渡した別の実1件が拒否された。前回のMarkdown/JSON guardはsuffixを強調していたため、hidden-partを除外する実catalogとの境界が操作の入口に反映されなかった。拒否後の同helper反復は行わず、観測済みのtrusted Git blobをboundedに読む経路へ切り替えた。

source helperを選ぶ場合の実行可能判定は次の通り。`catalog`はGit管理、suffix、hidden-part、work/build/dependency、secret名、symlinkを実装どおり検査する。candidateの推測実行で可否を調べない。

```sh
python3 - /absolute/operator /absolute/owner observed/relative/path <<'PY'
import importlib.util
from pathlib import Path
import sys
operator, owner, relative = sys.argv[1:]
spec = importlib.util.spec_from_file_location("source_catalog", Path(operator) / "scripts/read-source.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
print({"source_helper_allowed": relative in module.catalog(Path(owner).resolve())})
PY
```

実境界8件（通常TS/Pythonはallowed、Markdown/packageJSON/`.github` script/workflow/`.agents` skill/learning Markdownはexcluded）を同catalogで確認した。対象外のworkは明示ownerのwork限定reader、catalog済みMarkdown/JSONは前節、今回の残作業のcatalog済みGit blobは次の固定経路を使う。対象は先callの`git ls-tree`/`git ls-files`実出力から選び、秘密・設定の汎用readerにしない。

```sh
python3 - /absolute/owner HEAD observed/relative/path 1 30 <<'PY'
from pathlib import Path
import subprocess
import sys
owner, ref, relative, first, last = sys.argv[1:]
root = Path(owner).resolve()
path = Path(relative)
if path.is_absolute() or ".." in path.parts:
    raise SystemExit(2)
entries = subprocess.check_output(["git", "-C", str(root), "ls-tree", "-r", "--name-only", ref, "--", relative], text=True).splitlines()
if entries != [relative] or not (1 <= int(first) <= int(last)):
    raise SystemExit(2)
raw = subprocess.check_output(["git", "-C", str(root), "show", f"{ref}:{relative}"])
if len(raw) > 2 * 1024 * 1024:
    raise SystemExit(2)
output = f"{relative}:{first}-{last}\n" + "".join(raw.decode().splitlines(keepends=True)[int(first)-1:int(last)])
if len(output.encode()) > 3500:
    raise SystemExit(2)
sys.stdout.write(output)
PY
```
