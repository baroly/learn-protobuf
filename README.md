# java-protobuf — Protobuf 前後端二進位通訊學習專案

以 **Java 21 (Spring Boot 3 + Maven)** 為後端、**Vue 3 (Vite + TypeScript)** 為前端，從零實作一個 **100% 使用 Protocol Buffers (Protobuf) 二進位格式**通訊的即時聊天室（先走 HTTP REST `application/x-protobuf`，再進階至 WebSocket `BinaryMessage` / `ArrayBuffer`）。

---

## ★ 接續前必讀

- **目前進度**：
  - **Phase 0（`000001`～`000003`：`.proto` 定義、前後端骨架與自動 Codegen）已 100% 完成（2026-10-17）**。
  - **Phase 1（`000004`～`000006`：HTTP REST + Protobuf 二進位收發）已 100% 完成（2026-10-20）**：
    - 後端 `/api/messages` (`POST` / `GET` `application/x-protobuf`) 與前端 `chatApi.ts` (`fetch` + `ArrayBuffer` / `Uint8Array`) + 淺藍色聊天室 UI 已實機聯調通過。
  - **Phase 2（`000007`～`000009`：WebSocket + Protobuf 即時通訊聊天室）已 100% 完成（2026-10-21）**：
    - `000007 - Phase 2 任務拆解`：已完成。
    - `000008 - 後端 Spring WebSocket 二進位處理器（BinaryWebSocketHandler）與併發廣播測試`：已完成（2026-10-20），後端 14 個單元/整合測試全綠通過。
    - `000009 - 前端 WebSocket (binaryType = 'arraybuffer') 即時通訊封裝與多分頁聯調`：**已完成（2026-10-21）**。前端 `chatWs.ts` 與 `App.vue` 雙分頁 `< 100 ms` 即時廣播（含 `JOIN` / `CHAT` / `LEAVE` 二進位幀）實機驗收通過！
- **下一步**：
  - 架構路線圖（`docs/architecture.md`）規劃的 **Phase 0 ～ Phase 2 已全數達成**！
  - 若後續想進階練習 **Phase 3（Protobuf 協定演進與向後相容：新增欄位、`oneof`、`map`、欄位廢棄 `reserved` 等）**，可隨時提出開啟新階段規劃。
- **之後寫程式前一定要知道的事**：
  1. **實際目錄與 Package 結構**：後端模組位於 `demo/`（Java package 為 `com.demo.chat`，Protobuf 產生碼 package 為 `com.demo.chat.proto`），共用協定檔位於 `proto/demo/chat/chat.proto`（Protobuf package 為 `demo.chat`），前端位於 `frontend/`（匯入命名空間為 `import { demo } from './proto/chat'`）。
  2. **後端 REST 與 WebSocket 完整路由**：`demo/src/main/resources/application.yaml` 設有 `server.servlet.context-path: /api`，REST 路徑為 `/api/messages`，WebSocket 路徑為 `/api/ws/chat`（而在 `@WebMvcTest` 的 `MockMvc` 測試中不含 `context-path`，路徑為 `"/messages"`，詳見 [docs/notes/05](docs/notes/05%20-%20Spring%20MockMvc%20%E6%B5%81%E5%BC%8F%20API%EF%BC%88%E4%B8%89%E9%83%A8%E6%9B%B2%EF%BC%89%E8%88%87%20Protobuf%20%E4%BA%8C%E9%80%B2%E4%BD%8D%E6%B8%AC%E8%A9%A6%E8%A9%B3%E8%A7%A3.md)）。
  3. **`CountDownLatch` 雙門閂與 Mockito 三劍客（`mock` / `verify` / `ArgumentCaptor`）**：見 [docs/notes/04](docs/notes/04%20-%20%E4%BD%BF%E7%94%A8%20CountDownLatch%20%E9%9B%99%E9%96%82%EF%BC%88%E8%B5%B7%E8%B7%91%E6%A7%8D%EF%BC%8B%E7%B5%82%E9%BB%9E%E7%B7%9A%EF%BC%89%E9%80%B2%E8%A1%8C%E5%A4%9A%E5%9F%B7%E8%A1%8C%E7%B7%92%E9%AB%98%E4%BD%B5%E7%99%BC%E5%96%AE%E5%85%83%E6%B8%AC%E8%A9%A6.md) 與 [docs/notes/07](docs/notes/07%20-%20Mockito%20%E5%96%AE%E5%85%83%E6%B8%AC%E8%A9%A6%E4%B8%89%E5%8A%8D%E5%AE%A2%EF%BC%9Amock%EF%BC%88%E6%9B%BF%E8%BA%AB%EF%BC%89%E3%80%81verify%EF%BC%88%E8%AA%BF%E7%9B%A3%E8%A6%96%E5%99%A8%EF%BC%89%E8%88%87%20ArgumentCaptor%EF%BC%88%E6%8D%95%E6%89%8B%E6%89%8B%E5%A5%97%EF%BC%89.md)。
  4. **前端 `protobufjs`、WebSocket `binaryType = 'arraybuffer'`、TS 6.0 `Uint8Array` 泛型與 8KB 記憶體池必記要點**：
     - `ChatMessage.encode(msg).finish()` 才會拿到 `Uint8Array`；`repeated` 欄位元素在 `.d.ts` 中為 `$Properties`（詳見 [docs/notes/03](docs/notes/03%20-%20protobufjs%20%E9%9D%9C%E6%85%8B%E6%A8%A1%E7%B5%84%E7%94%9F%E6%88%90%20(pbjs%20%E8%88%87%20pbts)%20%E8%88%87%20TypeScript%20%E5%9E%8B%E5%88%A5%E7%B4%B0%E7%AF%80.md)）。
     - 瀏覽器原生 `WebSocket` 預設 `binaryType` 為 `'blob'`，建立連線後必須立即設定 `socket.binaryType = 'arraybuffer'`，才能在 `onmessage` 將 `event.data` 同步包成 `new Uint8Array(event.data)` 交由 `ChatMessage.decode()` 解碼。
     - 傳給 `fetch` `body` 或 `ws.send` 時，可用 `new Uint8Array(requestBytes)`（或 `requestBytes as Uint8Array<ArrayBuffer>`），**切勿傳入 `requestBytes.buffer`** 以免誤傳 `protobufjs` 底層的 8KB 共用記憶體池（詳見 [docs/notes/06](docs/notes/06%20-%20TypeScript%206.0%20Uint8Array%20%E6%B3%9B%E5%9E%8B%E9%99%90%E5%88%B6%E3%80%81Zero-Copy%20%E6%96%B7%E8%A8%80%E8%88%87%20protobufjs%208KB%20%E8%A8%98%E6%86%B6%E9%AB%94%E6%B1%A0%E9%99%B7%E9%98%B1.md)）。
  5. **IDEA Generated Sources 與 Wire Format 筆記**：見 [docs/notes/01](docs/notes/01%20-%20protoc-jar-maven-plugin%20%E8%88%87%20IDEA%20Generated%20Sources%20%E6%8E%9B%E8%BC%89.md) 與 [docs/notes/02](docs/notes/02%20-%20Protobuf%20%E4%BA%8C%E9%80%B2%E4%BD%8D%20Wire%20Format%20(TLV%20%E8%88%87%20Varint)%20%E8%88%87%20Schema%20%E8%A7%92%E8%89%B2.md)。
  6. **外部終端機跑 `mvn` / `npm` 注意事項**：系統環境變數預設 `JAVA_HOME` 若為 JDK 26，跑 `mvn` 時需指定 JDK 21/22；PowerShell 若限制執行 `.ps1`，可用 `npm.cmd` 代替 `npm`。
- **尚未驗證的假設**：
  - 無（Phase 0 ～ Phase 2 所有架構假設與硬約束皆已實機驗證通過）。

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
