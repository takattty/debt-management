# Frontend

- tracked E2Eと私有work probeのstatus判定は `statusFeedback(scope, expected)` を使い、期待文言で絞ってから元のexpectを行う。複数の通知・loadingが共存するため直接の `getByRole('status')` や `.first()` で曖昧さを隠さない。同文言が複数あるなら所有regionのLocatorをscopeにする。CI lintはhelper以外の直接status selectorを拒否する。
- 既存DBでguide/probeを実行する場合、mutation前に対象範囲・事前snapshot/fingerprint・source/runtime識別を所有workのartifactへ永続保存する。メモリだけのbefore比較で済ませず、結果不明時はGETで事実を確認してから再操作を判断する。

- React / TypeScript。UI文言とエラーは簡潔な日本語、識別子は英語。
- component/source名を過去文脈から推測しない。rootの `scripts/read-source.py QUERY --list` で同checkoutの実catalogを別callで観測し、次callでその実pathを読込む。別checkoutは `--worktree` を明示する。
- 顧客コード・請求書番号等の制約はbackendも検証する。UIだけのテナント制御は禁止。
- 認証はHttpOnly cookie session。Basic headerやpasswordの常時保持を使わず、passwordはlogin送信成功後に入力stateから除去する。ログ・localStorage・URLへ出力しない。
- login前に匿名CSRFを取得し、成功後はGET sessionで新CSRFを取得する。再読込はcookieからsessionを復元し、401またはCSRF_INVALIDで顧客データを破棄する。ACCESS_DENIEDは権限拒否としてsessionを維持し、失敗した変更を自動再送しない。
- API契約の正本は `contracts/openapi.yaml`。transportの型・DTOを手書きで二重管理しない。
- 契約変更後は `npm run generate:api` で `src/generated/schema.ts` を再生成する。生成ファイルを直接編集しない。
- `npm ci --cache ../work/npm-cache && npm run check:api && npm run lint && npm run typecheck && npm test && npm run build`。
- Cloudでは `.npmrc` の `cache=./work/npm-cache` で書込み可能なcacheを使用する。明示する場合もwork内を指定し、homeの既定cacheへの失敗を反復しない。
- 複数worktreeを扱うCoordinatorは、FE packageコマンドに `npm --prefix /absolute/checkout/frontend ...` を用いる。repository rootにpackage.jsonがあると仮定せず、source編集とpackage実行を別commandへ分ける。
- `CHROMIUM_PATH=/usr/bin/chromium npm run test:e2e` は起動済みの実backend/PostgreSQLに接続する。APIモックでE2E合格に代替しない。
- E2Eデータは架空のみ。失敗表示、重複、再読込後の永続化、別tenant分離を確認する。
- 認証E2Eのnetwork traceはpassword本文やsession cookieを含むため保存しない。HTML reportと失敗時screenshotで診断し、password入力はtype=passwordを維持する。
- 招待リンクはmemory内で一度表示し、画面を離れると破棄する。accept-invitationのfragmentは起動時とhashchange直後にhistoryから除去し、POST本文にだけ送る。
- pagehideではReact rootを同期unmountし、秘密を持つDOM/stateをBFCacheへ残さない。pageshow.persistedではURL/cookieから新しく起動し、旧招待token・入力済passwordを復元しない。通常PlaywrightはBFCache無効なのでproduction distのnative BFCache guardも維持する。
- E2Eは共通fixtureを使い、data-sensitive/password要素を画像でmaskする。自動DOM snapshotもbearerリンクを含み得るため、秘密を含まないerror-context attachmentで置き換える。
- 今回の受入対象は顧客・請求・入金取込・名義対応・消込/取消/履歴・滞留と回収対応・文面コピーまでの売掛金必須機能。source/契約/JAR/dist/runtimeが最新mainへ結合された証拠と一致する範囲で完成を報告し、prepared実証を最終mainの再実行と混同しない。
- 売掛金の仕上げ後に停止する。貸付元本は保留し、Loanへ自動継続しない。メール送信と利息計算は今回の対象外。
