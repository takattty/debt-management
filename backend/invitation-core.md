# Passive invitation core

V3 migration と invitation の状態・token 値を追加するだけの受動的な段階。
公開 API、権限、招待発行や受諾処理、seed はまだ変更しない。

- `InvitationToken`: SecureRandom32bytes、canonical な padding なし base64url43文字。
  DB 検索・保存は SHA-256 hash64hex のみ。toString は secret を出さない。
- `Invitation`: bearer token を持たず、期限と消費状態を表す snapshot。
  deadline ちょうどは利用できず、消費済みは再利用できない。
- DB は token hash を一意にし、creator / accepted user を Tenant と組み合わせた FK で検査する。
  accepted_at と accepted_user_id は一組。期限前にだけ accepted_at を保存できる。

後続 API は Clock 注入、row lock、User 作成・token 消費・監査を同じ transaction にまとめる。
Token の平文は発行応答の fragment URL と、匿名 inspect / accept の POST 本文だけで扱う。
手動配布リンクの所持を受諾権限として扱い、email配送による本人確認済みとはしない。
