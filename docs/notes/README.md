# 筆記

開發過程中遇到、但不屬於架構決策也不屬於某一份報告的知識點。

`docs/architecture.md` 記錄的是**這個專案決定了什麼**；
這裡記錄的是**為了做那些決定所需要理解的東西** —— 通用的技術原理，換一個專案也還是成立。

寫進來的判準：**踩過或差點踩到，而且下次還會遇到。**

| 筆記 | 講什麼 |
|---|---|
| [01 - protoc-jar-maven-plugin 與 IDEA Generated Sources 掛載](01%20-%20protoc-jar-maven-plugin%20%E8%88%87%20IDEA%20Generated%20Sources%20%E6%8E%9B%E8%BC%89.md) | 為什麼 `protoc-jar-maven-plugin` 預設輸出路徑會跟 Lombok `annotations` 衝突，以及如何讓 IDEA 正確識別 `target/generated-sources/protobuf` |
| [02 - Protobuf 二進位 Wire Format (TLV 與 Varint) 與 Schema 角色](02%20-%20Protobuf%20%E4%BA%8C%E9%80%B2%E4%BD%8D%20Wire%20Format%20(TLV%20%E8%88%87%20Varint)%20%E8%88%87%20Schema%20%E8%A7%92%E8%89%B2.md) | Tag byte 的 `1+4+3` 位元結構、字串(TLV)與整數(TV)的差異，以及為什麼二進位只傳編號與數值、語意全靠前後端編譯出的 `.proto` 類別還原 |
| [03 - protobufjs 靜態模組生成 (pbjs 與 pbts) 與 TypeScript 型別細節](03%20-%20protobufjs%20%E9%9D%9C%E6%85%8B%E6%A8%A1%E7%B5%84%E7%94%9F%E6%88%90%20(pbjs%20%E8%88%87%20pbts)%20%E8%88%87%20TypeScript%20%E5%9E%8B%E5%88%A5%E7%B4%B0%E7%AF%80.md) | `pbjs -t static-module -w es6 --es6` 與 `pbts` 的運作流程，以及為什麼 `repeated` 欄位的元素屬性在 TS 中型別為 `string \| null \| undefined`（Vue `:key` 需加 `?? ''`） |
| [04 - 使用 CountDownLatch 雙門閂（起跑槍＋終點線）進行多執行緒高併發單元測試](04%20-%20%E4%BD%BF%E7%94%A8%20CountDownLatch%20%E9%9B%99%E9%96%82%EF%BC%88%E8%B5%B7%E8%B7%91%E6%A7%8D%EF%BC%8B%E7%B5%82%E9%BB%9E%E7%B7%9A%EF%BC%89%E9%80%B2%E8%A1%8C%E5%A4%9A%E5%9F%B7%E8%A1%8C%E7%B7%92%E9%AB%98%E4%BD%B5%E7%99%BC%E5%96%AE%E5%85%83%E6%B8%AC%E8%A9%A6.md) | 如何用 `startLatch(1)` 與 `doneLatch(N)` 讓 10 個執行緒在同一微秒同時起跑搶鎖，並讓 JUnit 主執行緒安全等待驗收 |
| [05 - Spring MockMvc 流式 API（三部曲）與 Protobuf 二進位測試詳解](05%20-%20Spring%20MockMvc%20%E6%B5%81%E5%BC%8F%20API%EF%BC%88%E4%B8%89%E9%83%A8%E6%9B%B2%EF%BC%89%E8%88%87%20Protobuf%20%E4%BA%8C%E9%80%B2%E4%BD%8D%E6%B8%AC%E8%A9%A6%E8%A9%B3%E8%A7%A3.md) | 將 `mockMvc.perform(...).andExpect(...).andReturn()` 拆解為三部曲，詳解 `import static` 建造者、`contentTypeCompatibleWith` 與 `getContentAsByteArray()` |
| [06 - TypeScript 6.0 Uint8Array 泛型限制、Zero-Copy 斷言與 protobufjs 8KB 記憶體池陷阱](06%20-%20TypeScript%206.0%20Uint8Array%20%E6%B3%9B%E5%9E%8B%E9%99%90%E5%88%B6%E3%80%81Zero-Copy%20%E6%96%B7%E8%A8%80%E8%88%87%20protobufjs%208KB%20%E8%A8%98%E6%86%B6%E9%AB%94%E6%B1%A0%E9%99%B7%E9%98%B1.md) | 為什麼 TS 6.0 拒絕 `Uint8Array<ArrayBufferLike>` 傳入 `fetch` `body`、如何用 `as Uint8Array<ArrayBuffer>` 達成 Zero-Copy，以及為什麼誤傳 `requestBytes.buffer` 會把 `protobufjs` 的 8KB 共用記憶體池送出去 |
