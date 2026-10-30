# runn の式識別子と予約語

runn の Expr では `matches` は正規表現演算子であり、bind の変数名として参照すると字句解析で失敗する。用途を表す `paymentMatches` / `candidateMatches` を使う。

## 確認した二つの実観測

- 既存 collection 候補検索: `collections.yml` の `len(matches)` が `unexpected token Operator("matches") (1:35)`。修正 commit `4f7bac2d5e2fc2afd1ff1f006966563fd0f050d5` は `candidateMatches` へ変更した。
- 今回 proxy 入金検索: 未修正 commit `f2799ef7de20a355de7db7044a2dc7c6923eff70` の同じ式が同じエラーで停止した。API/SQL/Nginx の障害ではない。

旧 raw log の SHA256 は `8a95768c52fab7997e0d13ebb8b0b313aea165e34bbd6f1c6c3921c85f31fbe0`、今回 raw log は `0739452cb7ee27d4e0cd4f38ce89bde2302adc57d156f095b00744733c02a1c8`。既存ログの再読を第三の失敗として数えない。

## 次回の guard

既存で動作する用途付き bind 名を使い、bind と loop.until/test の参照を一緒に確認する。`runn list` はこの loop 式を評価しないため、list 成功を式検証の代わりにしない。
今回の book は裸の `matches` が無いことを確認し、pinned runn の runner 不要 bind/loop probe で `paymentMatches` の参照を実評価してから HTTP を再実行する。最後に同 DB の本番 proxy 受入を確認する。

独立した複数の走査は、既存入金 book と同じく結果と cursor suffix の両方へ別名を付ける。前の走査結果を次の loop.until が参照しないよう、preview/first/second を分ける。
