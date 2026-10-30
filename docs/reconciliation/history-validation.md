# 公開操作履歴の検証

履歴は実 PostgreSQL の一 SQL snapshot、複数行を含む版単位ページ、取消リンク、現在の表示情報、tenant 分離、GET 無書込を検証する。
通常 CI の `backend/runn/allocations.yml` でも確定直後・取消後・次ページを正規 HTTP で確認する。
空 cursor は省略と同じ先頭ページへ正規化する。非空の不正値、過大な版や page size は 400 とする。
対象 SHA で実行した結果と未実施事項を PR の表へ記録する。旧 906 の合格は新しい対象の合格として扱わない。
