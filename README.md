# java-protobuf — Protobuf 前後端二進位通訊學習專案

以 **Java 21 (Spring Boot 3 + Maven)** 為後端、**Vue 3 (Vite + TypeScript)** 為前端，從零實作一個 **100% 使用 Protocol Buffers (Protobuf) 二進位格式**通訊的即時聊天室（先走 HTTP REST `application/x-protobuf`，再進階至 WebSocket `BinaryMessage` / `ArrayBuffer`）。

---

## ★ 接續前必讀

- **目前進度**：
  - **Phase 0（`000001`～`000003`）已 100% 完成**。
  - **Phase 1（HTTP REST + Protobuf 二進位收發）進行中**：
    - `000004 - Phase 1 任務拆解`：已核准完成（拆為後端 `000005` 與前端 `000006` 兩份報告）。
    - `000005 - 後端記憶體訊息儲存庫與 Protobuf HTTP REST API（含併發與 MockMvc 測試）`：**進行中（步驟 1 已完成，停在步驟 2）**。
      - ✅ **步驟 1 已完成**：`ChatMessageStore` 與 `ChatMessageStoreTest`（含 `CountDownLatch` 10 執行緒併發寫入 1,200 筆與 1,000 筆容量淘汰驗證）已實作並通過測試。
      - ⏳ **步驟 2 待完成**：已建立 `ChatController` 空殼與 `application.yaml` (`context-path: /api`)；尚待實作 `ProtobufConfig`、`ChatController` 方法、`GlobalExceptionHandler` 與 `ChatControllerTest`。
- **下一步**：明天開工時，繼續完成 `000005` 的**步驟 2**（見 `docs/plans/phase 1/000005 - ...md` 第四節步驟 2），跑過 `mvn clean test` 後收尾 `000005` 並進入前端 `000006`。
- **之後寫程式前一定要知道的事**：
  1. **實際目錄與 Package 結構**：後端模組位於 `demo/`（Java package 為 `com.demo.chat`，Protobuf 產生碼 package 為 `com.demo.chat.proto`），共用協定檔位於 `proto/demo/chat/chat.proto`（Protobuf package 為 `demo.chat`），前端位於 `frontend/`（匯入命名空間為 `import { demo } from './proto/chat'`）。
  2. **Spring `context-path: /api` 與 `MockMvc` 路由注意事項**：目前 `demo/src/main/resources/application.yaml` 設有 `server.servlet.context-path: /api`，且 `ChatController` 設有 `@RequestMapping("chat")`（正式啟動時完整路徑為 `/api/chat`）。注意在寫 `@WebMvcTest` 的 `MockMvc` 測試時，**`MockMvc` 不會套用 `context-path`**，`mockMvc.perform(post(...))` 的路徑需與 Controller 的 `@RequestMapping` 對齊（例如 `"/chat"` 或 `"/messages"`）。
  3. **`CountDownLatch` 雙門閂併發測試技巧**：使用 `startLatch(1)`（起跑槍）與 `doneLatch(N)`（終點線裁判）確保多執行緒在同一微秒同時搶鎖，且 `doneLatch.countDown()` 務必放在 `finally` 區塊（詳見 [docs/notes/04](docs/notes/04%20-%20%E4%BD%BF%E7%94%A8%20CountDownLatch%20%E9%9B%99%E9%96%80%E9%96%82%EF%BC%88%E8%B5%B7%E8%B7%91%E6%A7%8D%EF%BC%8B%E7%B5%82%E9%BB%9E%E7%B7%9A%EF%BC%89%E9%80%B2%E8%A1%8C%E5%A4%9A%E5%9F%B7%E8%A1%8C%E7%B7%92%E9%AB%98%E4%BD%B5%E7%99%BC%E5%96%AE%E5%85%83%E6%B8%AC%E8%A9%A6.md)）。
  4. **前端 `protobufjs` 與 TypeScript 必記要點**：`ChatMessage.encode(msg).finish()` 才會拿到 `Uint8Array`；`repeated` 欄位元素在 `.d.ts` 中為 `$Properties`（屬性型別含 `null`），在 Vue `v-for` 綁定 `:key` 時需寫 `:key="msg.id ?? ''"`（詳見 [docs/notes/03](docs/notes/03%20-%20protobufjs%20%E9%9D%9C%E6%85%8B%E6%A8%A1%E7%B5%84%E7%94%9F%E6%88%90%20(pbjs%20%E8%88%87%20pbts)%20%E8%88%87%20TypeScript%20%E5%9E%8B%E5%88%A5%E7%B4%B0%E7%AF%80.md)）。
  5. **IDEA Generated Sources 與 Wire Format 筆記**：見 [docs/notes/01](docs/notes/01%20-%20protoc-jar-maven-plugin%20%E8%88%87%20IDEA%20Generated%20Sources%20%E6%8E%9B%E8%BC%89.md) 與 [docs/notes/02](docs/notes/02%20-%20Protobuf%20%E4%BA%8C%E9%80%B2%E4%BD%8D%20Wire%20Format%20(TLV%20%E8%88%87%20Varint)%20%E8%88%87%20Schema%20%E8%A7%92%E8%89%B2.md)。
  6. **外部終端機跑 `mvn` / `npm` 注意事項**：系統環境變數預設 `JAVA_HOME` 若為 JDK 26，跑 `mvn` 時需指定 JDK 21/22；PowerShell 若限制執行 `.ps1`，可用 `npm.cmd` 代替 `npm`。
- **尚未驗證的假設**：
  - `000005` 步驟 2 的 `ProtobufHttpMessageConverter` 掛載與 `MockMvc` `application/x-protobuf` 測試（明天完成步驟 2 時驗證）。
  - 前後端跨行程透過 HTTP `application/x-protobuf` 實際收發二進位封包（將於 `000006` 驗證）。

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
