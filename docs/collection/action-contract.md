# 回収対応と文面の契約

独立した CollectionActions interface で NOTE/保留/解除と確認用文面を先に定義する。契約だけの段階では新 API は未提供。既存 Collections Controller に未実装 method は追加しない。

NOTE は1〜5000 code points、保留・解除理由は1〜1000 code points。nextActionDate は必須 member で nullable、明示 null で消去する。version は回収状態独立で比較し、古い版は409。
完済・取消後も事後 NOTE と履歴は保持し、現在対象外の文面は409。文面は現在の実残高・宛名・請求番号・期日から手で確認してコピーし、送信しない。
