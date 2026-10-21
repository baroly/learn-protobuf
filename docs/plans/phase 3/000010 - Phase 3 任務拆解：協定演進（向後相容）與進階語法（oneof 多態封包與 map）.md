# 000010 - Phase 3 任務拆解：協定演進（向後相容）與進階語法（`oneof` 多態封包與 `map`）

**狀態**：待核准
**日期**：2026-10-21
**前置**：Phase 0（`000001`～`000003`）、Phase 1（`000004`～`000006`）與 Phase 2（`000007`～`000009`）已全數完成。

這份是 **Phase 3（Protobuf 協定演進、向後相容與進階語法 `oneof` / `map` 實戰）** 的規劃型報告。它本身不寫任何產品程式碼，核准的是「**Phase 3 的 `.proto` v2 升級藍圖，以及如何拆成後端（`000011`）與前端（`000012`）兩份實作報告**」。

做完 Phase 3 之後：
1. **親手驗證「向後／向前相容（Backward & Forward Compatibility）」**：
   - 你會在 JUnit 單元測試中親眼見證：把 Phase 0～2 舊格式（只有欄位 `1～5`）產生的二進位 `byte[]` 餵給新版 `ChatMessage` 解析、或是把帶有新欄位（`6, 7, 8`）的二進位封包還原並觀察 `UnknownFields`，為什麼 **100% 不會拋出例外或弄丟資料**，並學會用 `reserved` 鎖死廢棄欄位編號。
2. **掌握 `oneof` 多態封包（Polymorphic Payload）**：
   - 聊天室不再只能傳單調的純文字！我們在 `ChatMessage` 加入 `oneof payload`，讓同一則二進位訊息可以在 **「純文字訊息」** 與 **「動態貼圖訊息（`StickerPayload`）」** 之間二選一互斥切換，並在前後端學會用 `getPayloadCase()` / `msg.payload` 進行型別安全的多態分支處理。
3. **掌握 `map<string, string>` 鍵值對字典**：
   - 每則訊息可附帶 `map<string, string> metadata`（例如自動帶上發送端的客戶端標記 `client: "web-ws"`、語言或心情標籤），在聊天室氣泡旁顯示精緻的 Metadata 小標籤！

---

## 先對詞彙與 `chat.proto` v2 演進設計

Phase 3 預計對 `proto/demo/chat/chat.proto` 進行**非破壞性（Non-breaking）擴充**：

```protobuf
// 新增：貼圖負載結構（供 oneof 多態封包使用）
message StickerPayload {
  string pack_id = 1;       // 貼圖包代號，例如 "capoo"
  string sticker_code = 2;  // 貼圖代碼，例如 "thumbs_up", "fire", "party"
  string label = 3;         // 貼圖顯示名稱，例如 "咖波比讚"
}

message ChatMessage {
  // ★ 原有 1～5 號欄位編號與型別 100% 保持不動（確保與 Phase 0～2 雙向相容！）
  string id = 1;
  string sender = 2;
  string content = 3;
  int64 timestamp = 4;
  MessageType type = 5;

  // ★ Phase 3 新增：第 6 號欄位 — 鍵值對字典（相當於 Java Map<String, String>）
  map<string, string> metadata = 6;

  // ★ Phase 3 新增：第 7～8 號欄位 — oneof 多態互斥負載（同一時間只會存在其中一種）
  oneof payload {
    string rich_text = 7;          // 多態選項 A：進階文字內容
    StickerPayload sticker = 8;    // 多態選項 B：貼圖封包
  }

  // ★ Phase 3 新增：示範廢棄欄位編號保護鎖（禁止未來任何人誤用 99 號或 "legacy_flag" 名稱）
  reserved 99;
  reserved "legacy_flag";
}
```

| 語法特性 | 後端 Java 生成 API (`protoc`) | 前端 TypeScript 生成型別 (`pbjs` / `pbts`) |
|---|---|---|
| `map<string, string> metadata = 6;` | `.putMetadata(k, v)`、`.putAllMetadata(map)`、`msg.getMetadataMap()`、`msg.getMetadataOrDefault(k, defaultVal)` | `metadata?: { [k: string]: string }`（直接傳普通 JS 物件！） |
| `oneof payload { ... }` | `msg.getPayloadCase()` 回傳枚舉：`RICH_TEXT`、`STICKER` 或 `PAYLOAD_NOT_SET`；設定其中一個會自動清除另一個 | `msg.payload` 值為 `'richText' \| 'sticker' \| undefined` |
| `reserved 99; reserved "legacy_flag";` | 編譯期保護鎖：若有人新增 `= 99` 的欄位，`mvn compile` 會直接報錯攔截 | 同左，`npm run proto` 會報錯攔截 |

---

## 零、現況盤點

- **已有的東西**：
  - `proto/demo/chat/chat.proto` 已有 `ChatMessage`（欄位 `1～5`）、`MessageType` 與 `ChatHistoryResponse`。
  - 後端 `ChatMessageStore`、`ChatController` 與 `ChatWebSocketHandler` 都是直接儲存與轉發 `ChatMessage` 物件與 `byte[]`——**這正是 Protobuf 向後相容最神奇的地方：後端 Controller 與 WebSocketHandler 幾乎不用改半行程式碼，就能自動儲存並轉發帶有新版 `map` 與 `oneof` 欄位的二進位封包！**
- **缺口**：
  - `chat.proto` 尚未加入 `StickerPayload`、`map<string, string> metadata`、`oneof payload` 與 `reserved` 宣告。
  - 後端尚無針對「新舊協定二進位相容性、`oneof` 互斥行為、`map` 序列化」的專項測試。
  - 前端 `chatWs.ts` / `chatApi.ts` 與 `App.vue` 尚未提供貼圖多態訊息與 `metadata` 鍵值對的發送與渲染。

---

## 一、拆成 2 份報告

延續前兩階段的節奏，Phase 3 拆為 **後端協定演進與測試（`000011`）** 與 **前端多態封包與 Map 實戰（`000012`）** 兩份報告：

| 預留編號 | 報告名稱 | 範圍 | 為什麼這樣切 | 相依關係 |
|---|---|---|---|---|
| `000011` | **擴充 `chat.proto`（`oneof` 多態封包、`map` 與 `reserved`）及後端向後相容與多態單元測試** | 1. 親手升級 `proto/demo/chat/chat.proto` 加入 `StickerPayload`、`map<string, string> metadata`、`oneof payload` 與 `reserved`<br>2. 親手撰寫 `ChatSchemaEvolutionTest.java`（測試：① 模擬 Phase 1/2 舊版 1～5 號欄位 bytes 給新版解析的**向後相容**、② 模擬新版含 `map`/`sticker` 的 bytes 被只讀 1～5 號邏輯解析與 `UnknownFieldSet` 的**向前相容**、③ `oneof` 先設文字再設貼圖的**自動互斥覆蓋**與 `getPayloadCase()` 判斷、④ `map<string, string>` 讀寫驗證） | 先在後端透過 JUnit 測試把「向後相容、`oneof` 互斥、`map` 字典」三個核心觀念用鐵證測通，而且你會親眼見證既有的 18 個舊測試在 `.proto` 升級後依然 100% 全綠！ | 依賴 Phase 2 (`000009`) |
| `000012` | **前端 `oneof` 多態訊息（文字／貼圖）與 `map` 標籤收發封裝及聊天室 UI 升級** | 1. 執行 `npm run proto` 更新前端靜態模組<br>2. 親手擴充 `frontend/src/api/chatWs.ts`（支援發送 `StickerPayload` 貼圖多態封包與附帶 `metadata` 鍵值對）<br>3. 由 AI 更新 `App.vue` 新增「貼圖選擇列」與氣泡上的 `metadata` 標籤展示，雙分頁實機驗收貼圖與文字多態廣播 | 讓你在瀏覽器畫面上親眼看到 `oneof`（貼圖 vs 文字）與 `map` 在 WebSocket 二進位幀中的實際運作效果 | 依賴 `000011` |

---

## 二、對既有功能的衝擊與破壞性風險

- **資料遷移**：沒有。
- **對既有 Phase 0～2 程式碼與測試的衝擊**：
  - **0 破壞性！** 因為我們嚴格遵守 Protobuf 協定演進規範（不動既有 `1～5` 號欄位的編號與型別，只在後面追加 `6, 7, 8` 號），原有的 `ChatProtoTest`、`ChatMessageStoreTest`、`ChatControllerTest`、`ChatWebSocketHandlerTest` 共 **18 個測試** 在重新編譯後將 **100% 原封不動通過**！
- **已知風險**：
  - **舊欄位 `content = 3` 與新 `oneof payload` 的過渡配合**：在實務演進中，為了讓還沒升級的舊版前端也能看到貼圖提示文字，發送貼圖時除了設定 `oneof` 的 `sticker = 8` 之外，也可以同時在 `content = 3` 填入 fallback 文字（例如 `"[貼圖] 咖波比讚"`）。這正是業界真實的「平滑向後相容」技巧，我們會在 `000011` 與 `000012` 實戰演練！

---

## 三、替代方案與取捨

### A. 向後相容的驗證方式：在同一個 `chat.proto` 擴充並用 JUnit 模擬新舊封包互轉（★ 建議） vs 複製兩份 `chat_v1.proto` 與 `chat_v2.proto`

- **方案甲（★ 建議：直接在 `chat.proto` 擴充 v2，並在單元測試中構造 v1 精簡封包與 v2 完整封包加上 `UnknownFieldSet` 驗證）**：
  - **優點**：
    1. 保持專案「單一 `chat.proto` 真實來源」的乾淨結構。
    2. 搭配原本已經寫好的 18 個舊單元測試（它們全部只使用 v1 的 1～5 號欄位），當 `chat.proto` 升級到 v2 後，那 18 個舊測試一跑全綠，本身就是最強大的「向後相容」活教材！
- **方案乙（另外建一個 `chat_v1.proto` 與 `chat_v2.proto`）**：
  - **為什麼排除**：雖然也可以測試跨版本，但會在專案裡留下多份重複的 `.proto` 檔，與決策 2 的單一真實來源原則衝突。
  - *（註：如果在 `000011` 測試中你也想體驗「定義一個真的只有 1～5 號欄位的 `LegacyChatMessage` 來解析帶有 6～8 號欄位的 v2 bytes，親眼看它把 6～8 號收進 `getUnknownFields()`」，我們也可以在 `chat.proto` 裡加一個 5 行的 `LegacyChatMessageV1` 專供測試對照！兩種都可以兼顧！）*

---

## 四、步驟拆解與驗證方式

| 報告編號 | 目標 | 驗收標準（怎麼確認沒壞） | 預估時間 |
|---|---|---|---|
| `000011` | `chat.proto` v2 升級（`oneof` + `map` + `reserved`）與 `ChatSchemaEvolutionTest` | 於 `demo/` 執行 `mvn clean test`，既有 18 個測試 + 新增的相容性/多態/Map 測試全綠通過 | 約 25 分鐘 |
| `000012` | 前端 `npm run proto`、`chatWs.ts` 支援貼圖/Map 與 `App.vue` 雙分頁驗收 | 1. `npm.cmd run build` 通過<br>2. 雙分頁發送「貼圖封包」與「帶 Metadata 標籤的文字封包」即時同步渲染 | 約 20 分鐘 |

---

## 五、不在本任務範圍

- Protobuf `Any` 動態型別與 Well-Known Types (`google.protobuf.Timestamp`) —— 在已知所有訊息子類型的通訊協定中，`oneof` 的編譯期型別安全性與效能皆顯著優於 `Any`，因此本階段以業界最常用的 `oneof` + `map` 為核心。

---

## 六、我自己決定、核准時可以推翻的

| 決定 | 推翻的話怎麼改 |
|---|---|
| 在 `chat.proto` 同時加入一個 `LegacyChatMessageV1`（只含 1～5 號欄位）供 `000011` 單元測試模擬「舊版程式收到新版 `map`/`oneof` 二進位封包」時的向前相容行為 | 這樣你在寫 JUnit 測試時，可以把 `ChatMessage` (v2) 序列化出的 `byte[]`，直接丟給 `LegacyChatMessageV1.parseFrom(bytes)`，親眼驗證舊版程式真的不會炸裂，而且還能呼叫 `v1Msg.getUnknownFields()` 看到被保留下來的第 6、8 號未知欄位！若不想要這個測試對照 message 可於核准時告知 |
