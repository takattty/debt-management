# 消込 ledger の内部準備

Status: 未有効化。V7（名義対応）後の V8 を内部 ledger に予約する。公開 API、候補確定、既存 GET の残高置換、`InvoiceMutationEligibility` adapter はこの slice に含めない。

## 回答に依存しない保存則

- `payment.amount = active cash + unallocated`、`unallocated >= 0`。
- `invoice.amount = active cash + explicit fee + outstanding`、`outstanding >= 0`。
- 一部充当、複数請求、過入金の保持を表現する。手数料は明示値だけを保存し、差額から推定しない。現金・手数料は非負の整数、各 line はどちらかが正。`cash=0, fee>0` を storage で禁止しない。
- 和・差は Java `BigInteger` / PostgreSQL `numeric` で計算する。固定幅整数へ暗黙に狭めない。line と残高の許容上限は参照元の請求・入金額によって制約し、加算 overflow を成功扱いにしない。
- 原確定と取消は追記のみ。取消は元 operation 全体に一度だけ行い、全 line の cash/fee を同時に無効化する。履歴を削除・上書きしない。

## 回答待ちの policy

| 判断 | 未決事項 | この slice の境界 |
| --- | --- | --- |
| B06 | 1 入金を同 tenant の複数顧客へ充当できるか | tenant FK は必須。同一顧客だけという constraint / domain validation を追加しない。公開 command の policy は回答後に実装する |
| B07 | 手数料だけを後付けする独立操作が必要か | line / operation の cash 合計 0 を禁止しない。手数料後付けの公開操作、参照元、理由や版の UX は回答後に決める |

複数顧客や fee-only を内部 storage で表現できることは、業務操作の許可を意味しない。許可・拒否の command policy を公開前に明示する。

## Table と履歴

| Table | 役割・主な制約 |
| --- | --- |
| `reconciliation_payment_state` | `(tenant_id,payment_id)` ごとの可変 version。入金事実は UPDATE しない。未作成は version 0、内部 lock helper が lazy 初期化。削除・key 書換え・版の巻戻しを拒否 |
| `allocation_operation` | CONFIRM / REVERSE の原記録。payment、tenant 内一意 request ID、actor、理由、記録時刻、記録後 payment version。版は payment 内一意かつ連続。REVERSE は同 payment の CONFIRM だけを参照し、元 operation ごとの unique 制約で二重取消を拒否 |
| `allocation_line` | CONFIRM operation の invoice、観測した invoice version、cash / explicit fee。operation 内 invoice は一行。同 tenant FK。原 operation と同じ DB transaction 内にだけ追加できる |

operation と line は UPDATE / DELETE を trigger で拒否する。原 operation に後日 line を追加することも拒否する。CONFIRM は一行以上、REVERSE は独自 line を持たず原 line 全体を取り消す。payment は従来の immutable trigger を維持し、取引 ID の永久予約も変えない。

保存は一つの transaction で operation、全 line、state version を確定する。未完成の header、負残高、版の飛び・不一致は deferred constraint trigger で transaction 全体を拒否する。後続 command は同じ transaction に監査記録も追加する。この slice は command を提供しないため、監査付き確定が完成したとは扱わない。

## Lock と version

後続の確定・取消は共通 helper で次の順序を守る。

1. 認証済み tenant の payment state を `INSERT ... SELECT payment ... ON CONFLICT DO NOTHING` で lazy 初期化し、その一行を `FOR UPDATE`。
2. 対象 invoice 全行を DB の UUID 昇順で `FOR UPDATE`。不足 / 他 tenant は拒否。取消対象は元 operation の invoice 集合を使う。
3. payment state version と各 invoice version を照合し、保存則と回答済み policy を確認。state と invoice の version を加算時 overflow を検知して進め、operation / 全 line / audit を同時に保存。

invoice の既存訂正・取消は元の invoice row lock を使う。公開有効化時には実 ledger adapter を同じ lock 内で使い、cash または fee が 1 円でも有効なら直接変更を拒否する。現在の placeholder は置き換えない。invoice version は ledger 操作でも進める方針だが、invoice revision は請求事実の変更履歴、ledger operation は消込履歴として保持する。統合履歴 API と revision/version の表現は公開 slice の設計対象であり、この準備で既存挙動を変更しない。

DB の deferred 検証も payment state → 対象 invoice UUID 昇順で lock し、異なる payment から同じ invoice を同時に充当した場合も残高を再計算する。invoice 金額・状態変更の検証は既に保持する invoice lock だけで行い、payment lock を逆順に取得しない。複数 payment を一 command で扱う API は今回設計しない。

## 読取と有効化 guard

ledger の operation / line / payment state 書込と invoice の金額・状態 UPDATE は、DB trigger で **READ COMMITTED の transaction に限定**する。他 isolation は対象行や可視 allocation の有無にかかわらず statement 開始時に SQLSTATE `25000` で拒否する。readonly の REPEATABLE READ / SERIALIZABLE snapshot は利用できる。transaction policy を全アプリへ強制する設定変更は行わない。

この境界は保存則の前提である。親 row を物理更新しない `FOR UPDATE` だけでは高 isolation の snapshot は新しくならず、先に snapshot を作った RR/S transaction が、後から commit した別 payment の RC 充当を SUM で見落とせる。逆方向の invoice 減額・取消でも同じ欠陥が起きるため、visible allocation がある場合だけ guard する実装にはしない。すべてを SERIALIZABLE に統一した系の挙動を、RC と混在した系の安全性と同一視しない。将来ほかの write isolation を支援する際は、全 writer に共通する物理競合点と実混在回帰を別途設計する。

内部 repository は一 SQL snapshot 内で有効な CONFIRM（REVERSE がないもの）だけを集計し、現金と手数料を分離する。payment version 未作成は COALESCE 0。tenant 条件をすべての参照・集計に付ける。BigInteger domain が保存則を再検証し、DB 制約に反する結果を通常残高として返さない。

`debt.reconciliation.enabled` は無効のまま。次をそろえるまで公開しない：B06/B07 の回答、認証/権限/CSRF/version/idempotency/reason を持つ command、監査と全 batch rollback、invoice 訂正・取消との共通 lock と実 eligibility adapter、実 ledger による既存 GET 残高、候補は人が確定する UX、独立レビュー、実 HTTP と画面→DB の検証。

## この slice の検証範囲

実 PostgreSQL で partial / overpayment / 複数請求 / 明示 fee / fee-only を表現し、全 batch 取消で残高復元を検証する。tenant FK、request 重複、二重取消、append-only、後日 line 追加、空 header、整数/負値、過充当、版不一致、監査前段の transaction rollback、共通 lock と同 invoice の並行充当を検証する。B06/B07 の業務許可を検証済みとは報告しない。
