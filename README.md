# java-protobuf — Protobuf 前後端二進位通訊學習專案

以 **Java 21 (Spring Boot 3 + Maven)** 為後端、**Vue 3 (Vite + TypeScript)** 為前端，從零實作一個 **100% 使用 Protocol Buffers (Protobuf) 二進位格式**通訊的即時聊天室（先走 HTTP REST `application/x-protobuf`，再進階至 WebSocket `BinaryMessage` / `ArrayBuffer`）。

---

## ★ 接續前必讀

- **目前進度**：
  - **Phase 0（`000001`～`000003`）已 100% 完成**。
  - **Phase 1（HTTP REST + Protobuf 二進位收發）進行中**：
    - `000004 - Phase 1 任務拆解`：已核准完成（拆為後端 `000005` 與前端 `000006` 兩份報告）。
    - `000005 - 後端記憶體訊息儲存庫與 Protobuf HTTP REST API（含併發與 MockMvc 測試）`：**已完成（2026-10-19）**。後端 9 個單元/整合測試全綠通過。
    - `000006 - 前端 Vite Proxy、Protobuf fetch 二進位收發封裝與聊天室 UI`：**已核准，進行中（步驟 1 進行一半）**：
      - ✅ **步驟 1-1 已完成**：`frontend/vite.config.ts` 的 `/api` 代理轉發至 `http://localhost:8080` 已設定完成。
      - ⏳ **步驟 1-2 進行中**：已在 `frontend/src/api/` 建立檔案並寫到 `PacketMetric` 介面開頭。
      - ⏳ **步驟 2 待進行**：步驟 1 完成後交由 AI 代寫 `frontend/src/style.css` 與 `frontend/src/App.vue`（對齊 `crypt-fiction` 的 `#4596FF` 淺藍色系聊天室 UI）。
- **下一步（明天開工）**：
  1. **小提醒**：目前 `frontend/src/api/chatApi.vue` 建成了 `.vue` 元件檔（含 `<script setup>`），明天開工時請將它改名為純 TypeScript 模組 **`chatApi.ts`**（移除 `<script setup>` / `<template>` / `<style>` 外殼），因為它是給 `App.vue` 匯入呼叫的純 API 函式庫（在 `<script setup>` 內無法對外 `export function` 供其他元件匯入）。
  2. 照 `docs/plans/phase 1/000006 - ...md` 第四節步驟 1-2 完成 `chatApi.ts` 的 `sendMessageBinary` 與 `fetchHistoryBinary`。
  3. 告知 AI 由 AI 代寫步驟 2 的淺藍色 UI 並啟動前後端聯調驗收。
- **之後寫程式前一定要知道的事**：
  1. **實際目錄與 Package 結構**：後端模組位於 `demo/`（Java package 為 `com.demo.chat`，Protobuf 產生碼 package 為 `com.demo.chat.proto`），共用協定檔位於 `proto/demo/chat/chat.proto`（Protobuf package 為 `demo.chat`），前端位於 `frontend/`（匯入命名空間為 `import { demo } from './proto/chat'`）。
  2. **後端 REST API 完整路由為 `/api/messages`**：`demo/src/main/resources/application.yaml` 設有 `server.servlet.context-path: /api`，且 `ChatController` 設有 `@RequestMapping("messages")`（正式啟動時完整路徑為 `http://localhost:8080/api/messages`；而在 `@WebMvcTest` 的 `MockMvc` 測試中不含 `context-path`，路徑為 `"/messages"`，詳見 [docs/notes/05](docs/notes/05%20-%20Spring%20MockMvc%20%E6%B5%81%E5%BC%8F%20API%EF%BC%88%E4%B8%89%E9%83%A8%E6%9B%B2%EF%BC%89%E8%88%87%20Protobuf%20%E4%BA%8C%E9%80%B2%E4%BD%8D%E6%B8%AC%E8%A9%A6%E8%A9%B3%E8%A7%A3.md)）。
  3. **`CountDownLatch` 雙門閂併發測試技巧**：使用 `startLatch(1)`（起跑槍）與 `doneLatch(N)`（終點線裁判）確保多執行緒在同一微秒同時搶鎖，且 `doneLatch.countDown()` 務必放在 `finally` 區塊（詳見 [docs/notes/04](docs/notes/04%20-%20%E4%BD%BF%E7%94%A8%20CountDownLatch%20%E9%9B%99%E9%96%82%EF%BC%88%E8%B5%B7%E8%B7%91%E6%A7%8D%EF%BC%8B%E7%B5%82%E9%BB%9E%E7%B7%9A%EF%BC%89%E9%80%B2%E8%A1%8C%E5%A4%9A%E5%9F%B7%E8%A1%8C%E7%B7%92%E9%AB%98%E4%BD%B5%E7%99%BC%E5%96%AE%E5%85%83%E6%B8%AC%E8%A9%A6.md)）。
  4. **前端 `protobufjs`、TS 6.0 `Uint8Array` 泛型與 8KB 記憶體池必記要點**：
     - `ChatMessage.encode(msg).finish()` 才會拿到 `Uint8Array`；`repeated` 欄位元素在 `.d.ts` 中為 `$Properties`（詳見 [docs/notes/03](docs/notes/03%20-%20protobufjs%20%E9%9D%9C%E6%85%8B%E6%A8%A1%E7%B5%84%E7%94%9F%E6%88%90%20(pbjs%20%E8%88%87%20pbts)%20%E8%88%87%20TypeScript%20%E5%9E%8B%E5%88%A5%E7%B4%B0%E7%AF%80.md)）。
     - 傳給 `fetch` `body` 時，Zero-Copy 寫法為 `body: requestBytes as Uint8Array<ArrayBuffer>`（或 `new Uint8Array(requestBytes)`），**切勿傳入 `requestBytes.buffer`** 以免誤傳 `protobufjs` 底層的 8KB 共用記憶體池（詳見 [docs/notes/06](docs/notes/06%20-%20TypeScript%206.0%20Uint8Array%20%E6%B3%9B%E5%9E%8B%E9%99%90%E5%88%B6%E3%80%81Zero-Copy%20%E6%96%B7%E8%A8%80%E8%88%87%20protobufjs%208KB%20%E8%A8%98%E6%86%B6%E9%AB%94%E6%B1%A0%E9%99%B7%E9%98%B1.md)）。
  5. **IDEA Generated Sources 與 Wire Format 筆記**：見 [docs/notes/01](docs/notes/01%20-%20protoc-jar-maven-plugin%20%E8%88%87%20IDEA%20Generated%20Sources%20%E6%8E%9B%E8%BC%89.md) 與 [docs/notes/02](docs/notes/02%20-%20Protobuf%20%E4%BA%8C%E9%80%B2%E4%BD%8D%20Wire%20Format%20(TLV%20%E8%88%87%20Varint)%20%E8%88%87%20Schema%20%E8%A7%92%E8%89%B2.md)。
  6. **外部終端機跑 `mvn` / `npm` 注意事項**：系統環境變數預設 `JAVA_HOME` 若為 JDK 26，跑 `mvn` 時需指定 JDK 21/22；PowerShell 若限制執行 `.ps1`，可用 `npm.cmd` 代替 `npm`。
- **尚未驗證的假設**：
  - 前後端跨行程透過 HTTP `application/x-protobuf` 實際收發二進位封包（將於明天完成 `000006` 後驗證）。

---

## 專案結構

```text
java-protobuf/
├── proto/demo/chat/        # 前後端共用的 chat.proto 協定定義檔（唯一真實來源）
├── demo/                   # Spring Boot 3 (Maven) 後端專案
├── frontend/               # Vue 3 + TypeScript (Vite) 前端專案
├── docs/
│   ├── architecture.md     # 已核准的專案方向、硬約束、架構決策與路線圖
│   ├── decisions.md        # 實作過程中的決策偏離記錄
│   ├── plans/              # 開工前報告與階段索引
│   ├── notes/              # 踩坑與通用技術筆記
│   └── template/           # 討論檔暫存空間（不進版控）
└── .claude/skills/         # 開發流程與前後端程式碼規範
```

---

## 常用指令

```bash
# 後端：編譯 .proto 並執行單元測試（於 demo/ 目錄下）
mvn clean test

# 後端：啟動 Spring Boot 伺服器（於 demo/ 目錄下）
mvn spring-boot:run

# 前端：從 ../proto/demo/chat/chat.proto 產生 JS 與 TS 型別檔（於 frontend/ 目錄下）
npm run proto

# 前端：型別檢查與打包（於 frontend/ 目錄下）
npm run build

# 前端：啟動 Vite 開發伺服器（於 frontend/ 目錄下）
npm run dev
```
