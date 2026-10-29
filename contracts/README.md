# API contract

`openapi.yaml` がFE/BE間の契約源です。OpenAPI 3.0.3で顧客とsessionの5操作を記述しています。

- BE: Gradleの `openApiValidate` / `openApiGenerate` で検証し、OpenAPI Generator 7.17.0からSpring interface/DTOを生成します。生成先はbuild配下、手編集しません。Controllerが生成interfaceを実装します。
- FE: `npm run generate:api` でopenapi-typescript7.13.0から型を生成し、`check:api` で契約lintと生成差分を検出します。生成型はcommitし、手編集しません。
- `test:api-drift` は故意に古い出力を作り、checkが非0かつ書換えなしで拒否することを確認します。

上記consumer実装は後続のBE/FE依存PRに含めます。このPR単体は契約のみです。CIはRedocly CLI2.72.1でstruct/refs/operationを検証します。Basic認証はdev専用の架空認証であり、正式認証・招待はIssue #2です。
