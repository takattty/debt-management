# Failure learning loop

同じ原因による別の失敗を 2 回観測したら、同じ操作を繰り返す前に原因・予防策・検証結果・次回の guard を永続化する。失敗回数を増やすための再実行はしない。同じログや証拠を再読しただけの場合は、同じ event ID を使い重複計上しない。

各作業の開始時と関連する操作の直前に `failures.json` を読み、適用する guard を確認する。`check` が失敗した場合、対象原因への対策と検証を先に行う。依存しない作業は継続できる。解決後に同じ原因の新しい失敗が起きた場合は再度 open となり、既存の対策が不十分だった原因と改善を記録する。

tool結果はtop `content[].text` の短さで判断せず、JSON化した全envelopeのUTF-8総量を表示前に確認する。raw表示の代わりに `scripts/json-metadata.py` へ保存済みJSONを渡し、文字列本文ゼロ・depth/node制限・最終4KiB以内のmetadataだけを使う。[実nested事象と保存recipe](json-envelope-metadata-validation.md)を参照し、保存した結果を後からraw表示しない。

GitHub上流のGET500/502/503/504だけは合計最大3回のbounded retryとし、403/settings/billing拒否は再試行しない。POST/mergeの結果不明ではexact head/bodyを成功したGETで事実確認してから再操作を判断し、metadata不明ならmergeしない。[GitHub upstream transient guard](github-upstream-transient-validation.md)に実2件・判定手順・検証の境界を保存する。

## CLI

runn・画面E2Eは各testのID・顧客filter・keysetで自fixtureを選び、tenant全体の先頭順や空件数を仮定しない。既存データを削除せず、同じDBで複数回検証する。JUnitの専用架空DBにおける明示的なfixture初期化とは区別する。

先頭ページ仮定が必ず露呈する既存record baselineとして、通常API globの`backend/runn/zz-existing-invoices.yml`が各tenantへ古い未取消請求52件を正規APIで残す。verifyはこの同じDBで後続E2Eを実行する。baselineを消したりbookを省略して空DBの検証へ戻さない。検証記録は[Existing invoices baseline](existing-invoices-validation.md)。

scriptを初めて実行するときもsource名を推測しない。先のcallで `rg --files scripts` のcatalogを観測し、次callで観測済みpathを実行する。容量検査の実名を未確認のまま別名で実行した再発に対し、読込だけだったguardを実行時にも拡張した。

repository root で実行する。

```sh
python3 scripts/failure-loop.py record --key github-integration-permission --event public-patch-403-20261029 --summary '公開範囲変更APIが権限不足で拒否された' --evidence docs/runtime-evidence.md
python3 scripts/failure-loop.py check
python3 scripts/failure-loop.py resolve --key github-integration-permission --cause '付与されたintegration権限が操作に不足していた' --prevention '同種操作前に権限証拠を確認し、不足時は再試行せず阻害要因として保存する' --verification '実際に実行した検証と結果をここへ記載する' --guard docs/learning/README.md
```

上記は使用例であり、そのまま架空の検証結果を登録しない。`record` の key は原因を表す安定 ID、event は別の実行を識別する一意 ID とする。`resolve` は予防策を実装または手順へ反映し、検証した後に行う。guard は repository 内に実在するファイルの相対パスであり、次の関連作業で参照するルール、Skill、検査スクリプト等を指す。

通常の保存先は `docs/learning/failures.json`、JSON schema は `schema_version: 1`。記録は原子的に保存し、event を重複排除する。`check` の終了値は成功 0、同原因の未解決失敗が 2 回以上なら 1、不正な記録・guard 消失・読込不能なら 2。status は event 数と最新 resolution の対象 event 数から導出し、status 文字列の直接変更だけでは解決にならない。

`--store /tmp/.../failures.json` は使い捨て検証用。実運用の検査で保存先を切り替えて未解決記録を回避しない。guard はこのオプションに関係なく本 repository 内の実在ファイルに制限する。

## 記録する内容と境界

- 証拠は repository 内の相対パスまたは `https://github.com/` の参照 URL とし、raw error、認証情報、token、メールアドレス等の秘密を保存しない。URL の query/auth、絶対パス、repository 外への参照は受け付けない。
- summary、cause、prevention、verification も秘密を含まない要約にする。CLI が任意の文章中の秘密を完全に検出できるとは主張しない。
- このループはルールや権限を自動変更しない。観測済みの原因と検証済みの改善を保存し、将来の作業者が参照できる状態にする。
- 失敗を解決済みとして記録したことは、業務機能や merge gate の完了を意味しない。実際の検証と独立レビューは対象 commit に別途結び付ける。

mainの取得では `scripts/fetch-main.sh` を使い、明示先refとremote SHA一致を確認する。宛先なしfetchが成功してもorigin/mainが更新されるとは限らない。

patch適用前に対象の行全体を読み、部分行を完全なcontext行と仮定しない。長い段落の一部しか使えない場合は実在する短い見出しをanchorにする。context不一致の同じ操作を反復せず、再読してから最小差分を適用し、diffを検査する。

GitHub metadata取得は応答ごとの契約を確認する。PRのhead/baseはREST pulls/{number}のhead.sha/base.shaを用いる。combined statusはcreatorを省略するため、発行者確認はcommits/{sha}/statusesを用いる。gh pr viewのJSON選択肢はGraphQL全fieldと同じではなく、未対応field名を推測して反復しない。metadata不足ではmergeを止め、guard条件を緩めず正しいendpointへ直す。

2026-10-30のPR38再開で同じCLI field誤用が再発したため、head/baseは `python3 /absolute/active-checkout/scripts/gh-pr-metadata.py takattty/debt-management 38` で取得する。repositoryとPR番号は必須で、REST `repos/{repository}/pulls/{number}` を明示hostに対してGETする。出力は `number,url,state,merged,headRefOid,headRefName,baseRefOid,baseRefName` のJSON。head/base SHA・ref、PR番号・URL・状態の契約が欠けた場合は出力せず失敗し、API失敗の終了値も保つ。`gh-repo.sh` は `pr view` の `--json baseRefOid` / `--json=baseRefOid` をGitHub呼出し前に拒否してhelperへ案内する。レビュー・チェック等は引き続き対応fieldまたは対応endpointで取得する。実検証は [PR metadata validation](github-metadata-validation.md) に保存する。

worktreeと検証物の所有先を明示する。新worktreeを作るcommandは既存cwdから実行し、作成成功とHEADを確認してからそのpathをcwdに使う。別treeのwork配下の証拠は所有treeの絶対pathで読み、相対pathが現在のtreeを指すことを仮定しない。

GitHub CLIもcwdによるrepository推測に依存しない。`scripts/gh-repo.sh takattty/debt-management run view RUN_ID --json status,conclusion` のようにrepositoryを必須引数にする。省略時はCLIを起動せず拒否する。checkout外cwdと誤った継承GH_REPOでも選択先が固定される回帰テストをCIで実行する。

work証拠を相対pathで直接読む操作も再発したため、`python3 scripts/read-evidence.py work/name.log` で登録worktreeから所有先を自動解決する。同名が複数なら読み込まず拒否し、`--worktree /absolute/owner` を要求する。repo/証拠の所有先が異なる読込は別commandへ分ける。

helperが未導入の古い証拠checkoutから相対pathでhelperを呼ぶ失敗も観測した。Coordinatorはactive code checkout内のhelper絶対pathを使用する。helperの所有先と証拠の所有先は別に解決し、Docker等の操作は証拠readerと別commandで実行する。

`read-evidence.py`自身は絶対pathで呼ぶが、証拠引数は `work/name.log` の相対pathにする。所有treeは `--worktree /absolute/owner` で指定する。helperのpathと証拠引数の形式を混同しない。

行数だけでは単一巨大行のtool出力を制限できない。通常readerはheader込みUTF-8 16KiBを超えるとstdout空で拒否する。巨大JSON/logはfileへ保存し、`--json-chunk --offset N --size 4096` のbounded JSONを使う。owner/path・offset・実size・total・whole SHA256を揃えて再結合し、最後にhashを照合する。exec/tool応答の完了・exit0・truncationなしをJSON parseより先に確認し、切断prefixを除去してparseを再試行しない。2実失敗と実archive再結合の記録は[Evidence output validation](evidence-output-validation.md)を参照する。

source readerはcatalog/candidate・header・行番号を含む総stdoutをUTF-8 8KiBに制限し、超過はstdout空・exit2で拒否する。原則1 bounded readerを1 tool callで表示し、`max_output_tokens` は8192以上を確保する。各文書を短くしても合算token予算を超えるため、文字数・byte数・token数を混同せずstdout総UTF-8 byte数とwrapperの余裕を確認する。raw `git diff` とIssue/GitHub応答はwork保存、stdoutはID・URL・byte数・hash等の小さいscalar metadataだけにする。環境statusもready/enforced等のflagsに絞り、allowed-host一覧全文とsourceを同じ応答に出さない。固定版の実8件の履歴、8KiB guard、分割復元は[Source output validation](source-output-validation.md)を参照する。

最初のdiscoveryより先に指定operatorのAGENTS・learningを確認する。環境toolは戻り値の `structuredContent` からreadiness/stateのscalarだけを抽出し、raw objectを表示しない。worktree一覧とGitHub本文は先にworkへ保存し、path・ID・URL・bytes・hashだけを表示する。[実行recipeと追補](source-output-validation.md#最初のdiscoveryとscalar表示)は実truncationの最終15件と、切断のない環境全文表示near missを区別する。過去実行の遅延記録は予防後の再発回数と混同しない。token予算指定だけに頼らず、helper byte capと1reader1callとmetadata表示を組み合わせる。

新worktreeにはworkディレクトリがない。`verify.sh >work/log` はscript内のmkdirより先に失敗するため、Cloudの保存付き検証は `bash /absolute/checkout/scripts/verify-cloud.sh` を外側リダイレクトなしで実行する。wrapperが保存先を作り、verifyの終了値を保ったままwork/cloud-verify.logへ保存する。読み取りは既存readerで行う。

source classのfilenameも推測しない。最初に`rg --files`で対象packageの実在pathを列挙し、出力を確認した次のcommandで列挙済みpathだけを読む。discoveryと未確認の`cat`を同じbatchに組み込まず、名前の近いclassや責務からfilenameを作らない。

FE packageコマンドをrepository rootから実行してpackage.json不在となる再発も観測した。複数worktreeのCoordinatorは `npm --prefix /absolute/checkout/frontend ...` でpackage所有先を固定し、source編集とpackage実行を別commandへ分ける。cwdとpackage rootが一致するという仮定を持ち越さない。

複数fileのpatchでは、各fileの前に正しい `Update File` を一つ置く。同一pathへのDeleteとAddを一つのpatchへ重ねず、既存fileの置換はUpdateを使う。適用前に全file headerと実際のcontextを確認し、toolの拒否後は変更有無を確認してからpatchを作り直す。

source名の推測読込も再発したため、同checkoutの `read-source.py QUERY --list` を別callで先に観測し、次callに実出力の相対pathを渡す。helperはGit管理下の実在するJava/TS/JS/Python/shell/SQL/CSS/HTML等のsourceだけを対象にし、dotfile・work/build・既知のsecret名・symlink・未追跡fileを除外する。設定・鍵fileの汎用readerとして使わない。部分名または `--regex` で探し、一意なら `--start/--end` の範囲を表示する。複数候補・不一致は内容を読まず実catalog候補を返す。絶対path・親directory参照をqueryに渡さない。helper所有先と対象checkoutが違う場合はhelper絶対pathと `--worktree /absolute/target` を明示する。

Git blobの存在検査・hash proofもsource名guardの対象とする。対象SHAの `git ls-tree` / `git diff --name-only` を先のcallで観測し、次callで実出力のpathだけを `SHA:path` へ渡す。未catalogのfilenameを推測して `git rev-parse` へ埋め込まない。古い証拠treeでも最新operator側のAGENTS・learningとhelperを使い、対象treeを明示する。

Cloudの容量不足はworktree mkdir・Gradle report・E2E resultsの書込で別々に再発した。新worktree/worker前にdfと `scripts/check-workspace-capacity.py /workspace` で測定し、重い処理は1GiB未満・測定不能で停止する。verify-cloudはログ作成前、verifyは開始時と重いphase前、標準Linux CIも容量gateを必須実行する。完了と所有者を確認したtreeの再取得可能依存だけを、ignore状態・symlinkを確認して整理する。source・jar・DB・log/証拠・稼働中treeは保持し、無差別削除で回避しない。詳細は [Capacity validation](capacity-validation.md)。

APIの正常/異常系とbrowser検証は同runtimeで並行させずphaseを分ける。verifyはrunn/proxy完了後に同じartifactとDBでbackendを正常再起動し、process内login counterの共有を切る。閾値/window・CSRFを変えたり、固定sleepと429再送で検証を通したりしない。死活・port・容量・artifact照合と各phaseのlog/失敗終了値を保持する。相当検証harnessでもhelperとfixtureの所有checkoutをcatalogから固定し、古いcheckoutに新helperがあると仮定しない。実証は [Authentication phase validation](auth-phase-validation.md)。

APIのerror-code→message辞書は `Map.get` または `Object.hasOwn` で自身のkeyだけを参照する。HTTP clientを通す回帰では、実code・未知/欠落code・`constructor` / `toString` / `__proto__` を検査し、上位の個別案内が共通fallbackを誤って上書きしないことも確認する。
