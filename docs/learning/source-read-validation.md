# Source read guard validation

2026-10-30、Cloud、Python 3 stdlib。既存の `source-file-name-assumption` は、PR39のpreview checkoutにある旧2events・resolutionを継承した。FE ownerの2eventsとresolutionも当該keyだけをJSONで取り込み、他のledgerは上書きしていない。

## 再発

| event | 観測 |
| --- | --- |
| invoice-details-service-guessed-20261030 | Coordinatorが未列挙のInvoiceDetailsService.javaを推測してsedで読み、未存在の終了値2となった |
| invoice-ui-customerdetail-guessed-20261030 | FE ownerが未列挙のCustomerDetailを推測して読込に失敗した |
| invoice-ui-historyservice-guessed-20261030 | FE ownerが未列挙のInvoiceHistoryServiceを推測して読込に失敗した |

前回のREADME手順だけでは、過去文脈や責務から推測した名前を直接読込む操作が止まらなかった。新guardは `scripts/read-source.py`。Gitのtracked catalogから実在するsourceだけを探し、一意の実pathだけを読込む。複数候補と不一致では内容を出さず、実catalogの候補を返す。

## 検証

| 検査 | 結果 |
| --- | --- |
| 実Git fixture・別cwd・明示worktree | catalogを先callで列挙し、次callで観測pathの指定行だけを表示 |
| regex検索・複数候補 | 候補pathだけを表示し、内容を読まず終了値2 |
| 推測名不一致 | 近い実catalog候補だけを表示し、終了値1 |
| 絶対path・親参照・不正regex・不正範囲 | 内容を表示せず終了値2 |
| trackedのsecret名・dotfile・work・設定/鍵・symlink、未追跡・削除済file | catalogと読込対象から除外 |
| checkout内subdirectoryをroot指定 | cwdからrepositoryを推測せず終了値2 |
| 単体検証 | source reader 6 tests、Python全24 tests成功 |

Coordinatorは先に `debt-invoice-correction` checkoutで `rg --files` の実catalogを観測し、次のtoolcallでそこに存在した `InvoiceQueries.java` などを正常読込した。さらに新helper自身を使い、先callで `read-source.py Invoice --list --worktree /workspace/debt-invoice-correction` が21pathsを列挙し、次callで実出力由来の `backend/src/main/java/com/example/debt/invoice/InvoiceQueries.java --start 1 --end 65` を指定して46行を正常表示した。EOFを越える上限は実在行までで止まった。これはCoordinatorから共有された実行結果であり、未実行の検証を成功と記載していない。

新helperと実運用の二段階読込を確認してから、原因・予防・この検証・guardをresolveへ保存する。任意のsource本文にsecretがないことを検査するscannerではなく、既知secret名と非source fileを対象外にする限定readerである。業務コード・Java/PG実行・GitHub操作は変更していない。
