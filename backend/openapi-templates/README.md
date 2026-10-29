# Unicode string length validation

`beanValidationCore.mustache` は OpenAPI Generator **7.17.0** の
`JavaSpring/beanValidationCore.mustache` を基にした局所的な拡張。

JSON Schema / OpenAPI の `minLength` / `maxLength` は Unicode code point 数を表す。
Jakarta `@Size` が Java String に使う UTF-16 code unit 数とは一致しないため、
String の長さ制約だけを `CodePointSize` に置き換える。`@NotNull`、pattern、数値制約、
collection の `@Size` は upstream template のまま維持する。生成 DTO は手編集しない。
Generator 更新時は upstream template との差分を確認し、補助文字を含む API 境界テストを実行する。

JSON 型は `StrictJsonTypes` で Jackson の暗黙変換を拒否する。
小数から integer、string から integer、number/boolean から string への変換を認めない。
契約の許容文字種・最大長を狭めてこの不一致を回避しない。
