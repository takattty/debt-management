# Failure learning loop

同じ原因による別の失敗を 2 回観測したら、同じ操作を繰り返す前に原因・予防策・検証結果・次回の guard を永続化する。失敗回数を増やすための再実行はしない。同じログや証拠を再読しただけの場合は、同じ event ID を使い重複計上しない。

各作業の開始時と関連する操作の直前に `failures.json` を読み、適用する guard を確認する。`check` が失敗した場合、対象原因への対策と検証を先に行う。依存しない作業は継続できる。解決後に同じ原因の新しい失敗が起きた場合は再度 open となり、既存の対策が不十分だった原因と改善を記録する。

## CLI

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

worktreeと検証物の所有先を明示する。新worktreeを作るcommandは既存cwdから実行し、作成成功とHEADを確認してからそのpathをcwdに使う。別treeのwork配下の証拠は所有treeの絶対pathで読み、相対pathが現在のtreeを指すことを仮定しない。

GitHub CLIもcwdによるrepository推測に依存しない。`scripts/gh-repo.sh takattty/debt-management run view RUN_ID --json status,conclusion` のようにrepositoryを必須引数にする。省略時はCLIを起動せず拒否する。checkout外cwdと誤った継承GH_REPOでも選択先が固定される回帰テストをCIで実行する。

work証拠を相対pathで直接読む操作も再発したため、`python3 scripts/read-evidence.py work/name.log` で登録worktreeから所有先を自動解決する。同名が複数なら読み込まず拒否し、`--worktree /absolute/owner` を要求する。repo/証拠の所有先が異なる読込は別commandへ分ける。

helperが未導入の古い証拠checkoutから相対pathでhelperを呼ぶ失敗も観測した。Coordinatorはactive code checkout内のhelper絶対pathを使用する。helperの所有先と証拠の所有先は別に解決し、Docker等の操作は証拠readerと別commandで実行する。

`read-evidence.py`自身は絶対pathで呼ぶが、証拠引数は `work/name.log` の相対pathにする。所有treeは `--worktree /absolute/owner` で指定する。helperのpathと証拠引数の形式を混同しない。

新worktreeにはworkディレクトリがない。`verify.sh >work/log` はscript内のmkdirより先に失敗するため、Cloudの保存付き検証は `bash /absolute/checkout/scripts/verify-cloud.sh` を外側リダイレクトなしで実行する。wrapperが保存先を作り、verifyの終了値を保ったままwork/cloud-verify.logへ保存する。読み取りは既存readerで行う。

source classのfilenameも推測しない。最初に`rg --files`で対象packageの実在pathを列挙し、出力を確認した次のcommandで列挙済みpathだけを読む。discoveryと未確認の`cat`を同じbatchに組み込まず、名前の近いclassや責務からfilenameを作らない。

FE packageコマンドをrepository rootから実行してpackage.json不在となる再発も観測した。複数worktreeのCoordinatorは `npm --prefix /absolute/checkout/frontend ...` でpackage所有先を固定し、source編集とpackage実行を別commandへ分ける。cwdとpackage rootが一致するという仮定を持ち越さない。

複数fileのpatchでは、各fileの前に正しい `Update File` を一つ置く。同一pathへのDeleteとAddを一つのpatchへ重ねず、既存fileの置換はUpdateを使う。適用前に全file headerと実際のcontextを確認し、toolの拒否後は変更有無を確認してからpatchを作り直す。
