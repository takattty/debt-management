# Unicode string length validation

`beanValidationCore.mustache` は OpenAPI Generator **7.17.0** の
`JavaSpring/beanValidationCore.mustache` を基にした局所的な拡張。

JSON Schema / OpenAPI の `minLength` / `maxLength` は Unicode code point 数を表す。
Jakarta `@Size` が Java String に使う UTF-16 code unit 数とは一致しないため、
String の長さ制約だけを `CodePointSize` に置き換える。pattern、数値制約、
collection の `@Size` は upstream template のまま維持する。生成 DTO は手編集しない。
Generator 更新時は upstream template との差分を確認し、補助文字を含む API 境界テストを実行する。

`beanValidation.mustache` は同じ7.17.0 upstreamから、requiredかつnullableのpropertyだけを
`@NotNull` 対象外にする差分を持つ。requiredはJSON memberの存在、nullableは明示nullの許可であり、
両者を混同しない。CollectionActionRequestの必須nullable nextActionDateは限定RequestBodyAdviceで
member存在を検査する。missing400・明示nullで予定消去・通常の必須note/version拒否をAPI回帰で維持する。

JSON 型は `StrictJsonTypes` で Jackson の暗黙変換を拒否する。
小数から integer、string から integer、number/boolean から string への変換を認めない。
契約の許容文字種・最大長を狭めてこの不一致を回避しない。
