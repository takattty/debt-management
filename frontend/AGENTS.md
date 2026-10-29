# Frontend

- React / TypeScript。UI文言とエラーは簡潔な日本語、識別子は英語。
- 顧客コード・請求書番号等の制約はbackendも検証する。UIだけのテナント制御は禁止。
- 認証情報はmemoryだけに保持し、ログ・localStorage・URLへ出力しない。
- `npm ci && npm run lint && npm run typecheck && npm test && npm run build`。
- `CHROMIUM_PATH=/usr/bin/chromium npm run test:e2e` は起動済みの実backend/PostgreSQLに接続する。APIモックでE2E合格に代替しない。
- E2Eデータは架空のみ。失敗表示、重複、再読込後の永続化、別tenant分離を確認する。
- 現在は顧客登録の縦断基盤。請求・消込・督促等が完成したと報告しない。
