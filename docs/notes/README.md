# 筆記

開發過程中遇到、但不屬於架構決策也不屬於某一份報告的知識點。

`docs/architecture.md` 記錄的是**這個專案決定了什麼**；
這裡記錄的是**為了做那些決定所需要理解的東西** —— 通用的技術原理，換一個專案也還是成立。

寫進來的判準：**踩過或差點踩到，而且下次還會遇到。**

| 筆記 | 講什麼 |
|---|---|
| [01 - protoc-jar-maven-plugin 與 IDEA Generated Sources 掛載](01%20-%20protoc-jar-maven-plugin%20%E8%88%87%20IDEA%20Generated%20Sources%20%E6%8E%9B%E8%BC%89.md) | 為什麼 `protoc-jar-maven-plugin` 預設輸出路徑會跟 Lombok `annotations` 衝突，以及如何讓 IDEA 正確識別 `target/generated-sources/protobuf` |
| [02 - Protobuf 二進位 Wire Format (TLV 與 Varint) 與 Schema 角色](02%20-%20Protobuf%20%E4%BA%8C%E9%80%B2%E4%BD%8D%20Wire%20Format%20(TLV%20%E8%88%87%20Varint)%20%E8%88%87%20Schema%20%E8%A7%92%E8%89%B2.md) | Tag byte 的 `1+4+3` 位元結構、字串(TLV)與整數(TV)的差異，以及為什麼二進位只傳編號與數值、語意全靠前後端編譯出的 `.proto` 類別還原 |
