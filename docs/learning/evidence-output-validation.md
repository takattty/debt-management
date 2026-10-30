# Bounded evidence output

2026-10-30、起点base `b81fe8ac92c4aa20c71b151347e6235189a630b5`。最終baseはPR54後の`d267ba87fe6b1ac41c62ef1ae50d0e56ce7fe6cc`。既存learningの19keys/55events全履歴とbaseline book/fixture/testを保持し、本変更は新keyの2eventsを追加する。

## 観測と境界

| event | 根拠と実際の失敗 |
| --- | --- |
| gitdb-publish-metadata-truncated-20261030 | Coordinatorの引継ぎsummaryによる。Git DB公開metadataの巨大JSON stdoutがtool上限で切られ、`Warning: truncated`を含む結果のJSON.parseが失敗した。今回の担当者が元失敗を直接再観測したとは扱わない |
| docker-log-json-capture-truncated-20261030 | Coordinatorが今回の実行で観測し本タスクへ引き継いだ。停止済みDocker logs退避の巨大JSON stdoutが同じ上限で切られ、JSON.parseが失敗した。元ログはlocal acceptance treeに保持 |

旧readerは末尾1〜200行を選ぶだけで、単一巨大行を無制限にstdoutへ出していた。raw exec結果の完了・exit code・切断の検査も不足していた。圧縮size guardによる意図的停止や、同じ失敗記録の再読は別eventに数えない。改善の否定テストも実運用の新失敗へ数えない。

## Readerの契約

- 通常の使い方と既定末尾20行を維持する。header込みUTF-8出力は16,384byteまで。日本語・不正UTF-8の置換後も実byte数で検査し、超過時はexit2・stdout空で明示拒否する。大きい過去行があっても、指定した小さい末尾だけなら読める。入力全体や巨大1行をmemoryへ読み込まない。
- 登録worktreeからownerを一意に決定する。同名artifactは`--worktree`が必須。絶対artifact、親directory参照、work外へのsymlink、work directory自体のsymlinkを拒否する。artifactを変更・削除しない。
- `--json-chunk`はEvidence headerなしのJSON一個だけを返す。`--offset`は非負のbyte位置（既定0）、`--size`は1〜4096byte（既定4096）。offsetは4096を超えてよい。末尾では実sizeが小さくなり、空fileまたはoffset=totalではsize0になる。offset>totalは拒否する。
- JSONは`artifact`、解決済み`path`、`offset`、実`size`、全fileの`total`、全fileの`sha256`、`encoding: base64`、`dataBase64`を持つ。byte単位なのでUTF-8文字の途中でも復元できる。JSON全体にも16KiB上限を適用する。
- hashはstreamで計算し、読み取り前後でfile size・mtime・ctime等が変わればpayloadなしで拒否する。複数chunk間も同じpath/total/SHAを要求し、writer停止後の安定したartifactを使う。

使用例（placeholderは実際のcatalog確認済みpathに置き換える）:

```sh
python3 /absolute/active-checkout/scripts/read-evidence.py work/result.json \
  --worktree /absolute/owner --json-chunk --offset 0 --size 4096
```

## 呼出側のguard

巨大JSONは最初からwork fileへ保存し、toolへは小さいmetadata/chunkだけを出す。toolの出力budgetはchunk応答が収まる値にする（4096byte chunkは通常約6KiBのJSON、例えば8,192 output tokensを確保する）。小さいchunkでも、tool envelopeが未完了・非exit0・切断ありならparseしない。

1. execが終了していること、exit code 0、toolのtruncation表示がないことを確認する。実行中sessionは完了を待つ。
2. bounded stdoutをJSONとしてparseする。`Warning: truncated`等のprefixを除去して無理にparseし直さない。欠けたsuffixは復元できない。
3. requested owner/path・offset、base64のstrict decode、実size≤4096、全応答のtotal/SHA一致を確認する。offsetは実sizeだけ進める。total未到達でsize0なら停止する。
4. 結合byte数とwhole SHA256が一致した後、必要なJSON parseや展開をlocal fileに対して行う。再結合した巨大payloadをtool stdoutへ戻さない。

## 実artifactでの確認

既存artifactは`/workspace/debt-issue3-local-acceptance/work/docker-archive.b64.json`、raw logsは同treeの`work/docker-archive/*.log`。新readerでの単位はJSON file自体のbyteで、内部の圧縮/base64 archiveとは区別する。

| 検証 | 実結果 / 改善treeのwork証拠 |
| --- | --- |
| 通常readerの拒否 | 実artifactは27,765byteの単一行。exit2・stdout空を確認 |
| chunk再結合 | 7chunks、最大応答5,739byte、全chunkのpath/offset/size/total/SHAを確認。`work/docker-chunk-proof.json`、`work/docker-chunks/` |
| JSON file hash | `31ddf497a9821216c728b1f680485c25683366ed9b86a38d608e42bda348b0d7`が元fileと再結合後で一致 |
| 内部archive | 展開後112,159byte、SHA256 `26593d956e814d21ef29473246df6511b119b056496aafd86cb2bdc9b8c3a18b`。Coordinatorの先行成功proofと照合 |
| 保持 | 元artifactとraw logsを含む13fileの前後hash不変。source/JAR/DBへ変更なし |
| 回帰 | reader15 tests成功。巨大単一行、日本語byte/UTF-8置換、16KiB境界、小さい末尾、empty、owner/escape、4096byte境界、不正range、全chunks再結合SHA、途中file変更を確認 |
| 全Python | 起点baseで49 tests成功（`work/python-tests.log`）、最新baseで50 tests成功（`work/python-tests-final.log`）。helper/testsはrebase前後byte同一 |

BE/FE/runtime/Compose再実行はhelperだけの変更なので対象外。GitHub書込・独立review・CI成功はこの作者実証へ含めない。
