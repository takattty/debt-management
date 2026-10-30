# Compose検証：請求・入金の統合候補

## 対象と範囲

検証対象は準備済みの統合候補 `6e8f77a7ed847fce3e9d24b329aca7dabdafe716` です。mainとは別の `acceptance/invoice-payment-compose` branchで、請求訂正・取消、入金CSV・一覧の後続PRの成果物も含みます。この検証だけで各PRの独立レビュー・CI・mergeを完了扱いにはしません。消込・督促・貸付金は未完成です。

Cloudで架空データだけを使い、digest固定のNginx / Java25 / PostgreSQL18をComposeで起動しました。実行projectは `debt-ar-acceptance`、HTTPはloopbackの8091です。他projectとDBを共有していません。

## 結果

| 検証 | 結果 |
| --- | --- |
| build-local / runtime image作成 | 成功。テスト成功とは区別 |
| 本番用Nginxを通すrunn | 10冊、失敗・skip 0 |
| 実BE/DBの画面E2E | 18件成功 |
| production buildのnative BFCache | 2件成功。専用の架空API fixtureによるブラウザーlifecycle試験 |
| コンテナの停止・再作成 | `compose down` → `up -d`、volumeを保持。3コンテナのIDが変更 |
| 再作成直後のDB | 業務8テーブルの全行の件数・内容指紋が一致 |
| 再起動後の画面E2E / native BFCache | 18件 / 2件成功 |
| 対象ソースの一致 | image build時commit `2001839…` と検証commitの本番BE・契約・FE・Nginx・Compose/Dockerfile差分ゼロ。差はテスト分離の修正だけ |

再作成直前と直後の照合値です。JSON化した全行を並べてMD5を計算し、件数と指紋の両方を比較しました。これはデータ残存の照合で、認証や暗号用途ではありません。

| テーブル | 件数 | 内容指紋（前後同値） |
| --- | ---: | --- |
| customer | 22 | `4303094f3e02ada5014236120cf26097` |
| invoice | 10 | `3e6367323de1480f5f6f710e2c642f0f` |
| invoice_revision | 18 | `95a4d4252a7951bb21c06963c96841a9` |
| payment | 58 | `72fd55eb30f23f1f2fd9b146b151d85b` |
| import_batch | 29 | `e7d00bb20b746d2a377abaeee65b275e` |
| import_row | 150 | `1eca554c379c32a8fca25c7b5ae5420c` |
| import_error | 70 | `a2daadd7c358b94ad7290c478cfd2f1d` |
| audit_event | 198 | `7a97e8abe9075b0ab14628edab02cc41` |

## 再現手順と証拠

JDK25・Node24.19・対応npm・Docker Composeを用意し、READMEどおり `.env.example` を `.env` へコピーして架空seedのpasswordを設定します。通常利用の停止でvolumeを削除しません。

```sh
scripts/build-local.sh
docker compose up --build -d
# HTTPの起動を待って、同じLOCAL_SEED_PASSWORDを設定して実行
RUNN_BASE_URL=http://127.0.0.1:8088 scripts/runn.sh run --scopes read:parent backend/runn/*.yml
E2E_BASE_URL=http://127.0.0.1:8088 npm --prefix frontend run test:e2e
docker compose down
docker compose up -d
```

Cloudの所有checkoutは `/workspace/debt-ar-compose-acceptance`。ignoredなworkには `compose-build-local.log`、`compose-up.log`、`compose-runn.log`、`compose-e2e.log`、`compose-business-before.txt` / `compose-business-after.txt`、`business-fingerprint.sql`、`compose-container-ids-before.txt` / `compose-container-ids-after.txt`、`compose-down.log`、`compose-restart.log`、`compose-e2e-after-restart.log` を保存しています。ログはownerを指定した `read-evidence.py` で読みます。

UUIDを含む架空fixtureは実行ごとに変わります。再現時は上表の値との一致ではなく、その実行の停止前後を比較します。
