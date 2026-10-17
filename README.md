# java-protobuf — Protobuf 前後端二進位通訊學習專案

以 **Java 21 (Spring Boot 3 + Maven)** 為後端、**Vue 3 (Vite + TypeScript)** 為前端，從零實作一個 **100% 使用 Protocol Buffers (Protobuf) 二進位格式**通訊的即時聊天室（先走 HTTP REST `application/x-protobuf`，再進階至 WebSocket `BinaryMessage` / `ArrayBuffer`）。

---

## ★ 接續前必讀

- **目前進度**：
  - **Phase 0（地基：`.proto` 定義、前後端專案骨架與自動 Codegen）已 100% 完成！**
    - `000001 - Phase 0 任務拆解`：已完成。
    - `000002 - 定義共用 chat.proto 與後端 Maven Protobuf 編譯＋序列化單元測試`：已完成（4 個 JUnit 測試全綠，單筆訊息 Protobuf **34 bytes** vs JSON **93 bytes**，節省 63.4%）。
    - `000003 - 建立前端 Vue 3 骨架與 protobufjs 靜態 Codegen 驗證`：已完成（`npm run proto` 與 `npm run build` 全綠通過，前端 `Uint8Array` 序列化與反序列化驗證完成）。
- **下一步**：進入 **Phase 1（HTTP REST + Protobuf 二進位收發）**，先產出規劃報告 `000004 - Phase 1 任務拆解`（規劃後端 `ProtobufHttpMessageConverter` + 記憶體訊息儲存庫 + `POST/GET /api/messages` 與 `MockMvc` 測試，以及前端 `fetch` 二進位收發與聊天室畫面）。
- **之後寫程式前一定要知道的事**：
  1. **實際目錄與 Package 結構**：後端模組位於 `demo/`（Java package 為 `com.demo.chat`，Protobuf 產生碼 package 為 `com.demo.chat.proto`），共用協定檔位於 `proto/demo/chat/chat.proto`（Protobuf package 為 `demo.chat`），前端位於 `frontend/`（匯入命名空間為 `import { demo } from './proto/chat'`）。
  2. **IDEA Generated Sources 掛載技巧**：`protoc-jar-maven-plugin` 必須設定 `<outputDirectory>${project.build.directory}/generated-sources/protobuf</outputDirectory>` 才不會跟 Lombok 的 `annotations` 父子目錄衝突（詳見 [docs/notes/01](docs/notes/01%20-%20protoc-jar-maven-plugin%20%E8%88%87%20IDEA%20Generated%20Sources%20%E6%8E%9B%E8%BC%89.md)）。
  3. **Protobuf 二進位底層結構（TLV 與 Varint）**：Tag byte 由 `1 bit 續讀位 + 4 bits 欄位編號 + 3 bits 型別代號` 組成；字串為 TLV（最少 3 bytes），整數/枚舉為 TV（無長度 byte，最少 2 bytes），語意還原全靠前後端各自編譯出的 `.proto` 類別（詳見 [docs/notes/02](docs/notes/02%20-%20Protobuf%20%E4%BA%8C%E9%80%B2%E4%BD%8D%20Wire%20Format%20(TLV%20%E8%88%87%20Varint)%20%E8%88%87%20Schema%20%E8%A7%92%E8%89%B2.md)）。
  4. **前端 `protobufjs` 與 TypeScript 必記要點**：`ChatMessage.encode(msg).finish()` 才會拿到 `Uint8Array`；`repeated` 欄位元素在 `.d.ts` 中為 `$Properties`（屬性型別含 `null`），在 Vue `v-for` 綁定 `:key` 時需寫 `:key="msg.id ?? ''"`（詳見 [docs/notes/03](docs/notes/03%20-%20protobufjs%20%E9%9D%9C%E6%85%8B%E6%A8%A1%E7%B5%84%E7%94%9F%E6%88%90%20(pbjs%20%E8%88%87%20pbts)%20%E8%88%87%20TypeScript%20%E5%9E%8B%E5%88%A5%E7%B4%B0%E7%AF%80.md)）。
  5. **外部終端機跑 `mvn` / `npm` 注意事項**：系統環境變數預設 `JAVA_HOME` 若為 JDK 26，跑 `mvn` 時需指定 JDK 21/22；PowerShell 若限制執行 `.ps1`，可用 `npm.cmd` 代替 `npm`。
- **尚未驗證的假設**：
  - 前後端跨行程透過 HTTP `application/x-protobuf` 實際收發二進位封包與 CORS 設定（將於 Phase 1 驗證）。

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
