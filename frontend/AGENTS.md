# Frontend

- React / TypeScript。UI文言とエラーは簡潔な日本語、識別子は英語。
- 顧客コード・請求書番号等の制約はbackendも検証する。UIだけのテナント制御は禁止。
- 認証はHttpOnly cookie session。Basic headerやpasswordの常時保持を使わず、passwordはlogin送信成功後に入力stateから除去する。ログ・localStorage・URLへ出力しない。
- login前に匿名CSRFを取得し、成功後はGET sessionで新CSRFを取得する。再読込はcookieからsessionを復元し、401またはCSRF_INVALIDで顧客データを破棄する。ACCESS_DENIEDは権限拒否としてsessionを維持し、失敗した変更を自動再送しない。
- API契約の正本は `contracts/openapi.yaml`。transportの型・DTOを手書きで二重管理しない。
- 契約変更後は `npm run generate:api` で `src/generated/schema.ts` を再生成する。生成ファイルを直接編集しない。
- `npm ci --cache ../work/npm-cache && npm run check:api && npm run lint && npm run typecheck && npm test && npm run build`。
- Cloudでは `.npmrc` の `cache=./work/npm-cache` で書込み可能なcacheを使用する。明示する場合もwork内を指定し、homeの既定cacheへの失敗を反復しない。
- `CHROMIUM_PATH=/usr/bin/chromium npm run test:e2e` は起動済みの実backend/PostgreSQLに接続する。APIモックでE2E合格に代替しない。
- E2Eデータは架空のみ。失敗表示、重複、再読込後の永続化、別tenant分離を確認する。
- 認証E2Eのnetwork traceはpassword本文やsession cookieを含むため保存しない。HTML reportと失敗時screenshotで診断し、password入力はtype=passwordを維持する。
- 現在は顧客登録の縦断基盤。請求・消込・督促等が完成したと報告しない。
