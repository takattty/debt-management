# Ledger prep の source / runn scalar 再発防止

2026-10-30 の V8 公開残高・請求変更guard準備で、未列挙の auth-test 設定名と旧入金book名を
それぞれ推測して読もうとし、実在しないため失敗した。前のtreeで見た構成をこのprepへ持ち込んだ。
さらに追加bookのbind式をYAML plain scalarにし、空白後の `#` がcommentとして解釈され式が途中で切れた。
いずれも業務データへの書込前の失敗で、guardを外さず手順/fixtureを修正した。

再発防止:

- Java等はactive checkoutのread-source helperでcatalogを取得し、次callで観測した一意の完全pathだけを読む。
  旧prepにhelperがあると仮定しない。今回もInvoiceMutationConfiguration検索はmain/testの2候補を返して読取を拒否し、
  次callで観測済みmainの完全pathを指定すると25行だけを取得した。
- helperの対象外である設定・YAML・runn名も、`rg --files backend/src backend/runn`でcatalogを実際に確認した
  次callで読む。通常profileからtest専用設定名を組み立てない。API責務からbook名を作らない。
  今回catalogはapplication.yml/application-local.ymlとpayment-import.yml/payment-candidates.ymlを返した。
- runnのbind/test式に `#` がある場合はquotedまたはblock scalarを使う。新bookのbind2箇所を引用し、
  一意の別fixtureを使う実HTTP2回と既存関連3bookを成功させた。

証拠は専用treeのwork/source-catalog-validation.log、work/runn-ledger-first.log、work/runn-ledger-second.log。
後続mainへの合成では新eventだけをevent ID unionで追加し、prepの旧ledger全体でmain履歴を置換しない。
