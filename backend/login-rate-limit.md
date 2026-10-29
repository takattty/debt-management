# Local single-process login limits

有効な入力のloginはDB lookup / password hash検証前にemailとremote addressの枠を予約する。
Canonical emailは失敗+検証中の合計10件/60秒、成功時は失敗をresetするが他の検証中の枠は残す。
Remote addressは60試行/60秒、成功でもresetしない。email上限で拒否した試行もIP枠に数える。
`getRemoteAddr()`だけを使い、clientのX-Forwarded-Forを信頼しない。cookie交換では枠をresetできない。

MapはSHA-256鍵のみを保持し、email/IP合計10000keysまで。期限切れの未使用枠をpurgeし、
容量超過は新規keyを追加せず429へfail closed。進行中の仕事は期限/成功でも追跡し、並行10件超えを防ぐ。
失敗経路はcloseで解放し、session保存と監査成功後だけsuccessを確定する。429 RATE_LIMITEDは
Retry-Afterを1〜60秒で返す。秘密や鍵をapplicationログへ出力しない。

Clockは専用beanで、テストだけ固定Clockを注入する。本番に閾値無効化やtime-travel APIは持たない。
単一BEのメモリ内stateであり、再起動で消える。複数instanceの分散制限は今回のlocal範囲外。
JUnitは期限・容量・並行予約・成功reset・IP上限・XFF/cookie改変をsleepなしで確認する。
runnは新cookieを毎回発行し、未登録の一意emailで10回401→11回429とOpenAPI/Retry-Afterを検証する。
