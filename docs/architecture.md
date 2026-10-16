# java-protobuf 架構

**狀態**：已核准（2026-10-16）
<!-- 狀態流轉：骨架 → 草稿 → 已核准（YYYY-MM-DD）。CLAUDE.md 的「第零道門」看這一行。 -->

這份是方向與架構決策的**主要真實來源**。實作中偏離這裡的決定記在 `docs/decisions.md`（追加不覆寫），兩份一起讀才是完整的歷史。

---

## 一句話定位

給開發者本人學習 **Protocol Buffers (Protobuf)** 前後端二進位通訊協定用的**教學型即時通訊軟體**（後端 Java + Spring Boot、前端 Vue 3）。

---

## 硬約束

**每一項都要有數字。** 沒有數字的不是約束，無法拿來否決任何設計。

| 約束 | 值 | 為什麼是這個值 |
|---|---|---|
| 第一階段與最終規模目標 | 支援 **10 個瀏覽器分頁**同時連線、雙向廣播延遲 **< 100 ms**、記憶體保留最近 **1,000 則**訊息 | 本機單機驗證多客戶端即時通訊與歷史訊息查詢，不需引入分散式架構，但需確保執行緒安全與容量上限 |
| 資源與環境天花板 | **1 台** Windows 11 本機開發機（JDK 26、Maven 3.8.6、Node.js v22.22.3）、**0 外部伺服器／0 資料庫容器** | 排除 Docker / DB 等基礎設施干擾，100% 聚焦在 Protobuf 序列化與前後端通訊協定 |
| 業務傳輸協定純度 | 業務 API（HTTP 聊天端點與 WebSocket 幀）**100% 為 Protobuf 二進位（0% JSON）** | 專案核心目的是學會 Protobuf 序列化、Content-Type 協商與 ArrayBuffer 二進位幀處理 |
| 教學與協作模式 | **100% 由使用者親手撰寫產品程式碼**；每新增 **1 個套件**必須在報告中附上「套件定位、常用 API 與必記要點」 | 這是學習專案，AI 角色為教練與審查者（撰寫含教學與範例碼的施工報告、回答 `@ask`/`@but`、執行建置驗證），不代寫產品碼 |

---

## 範圍

### 各階段做什麼

- **Phase 0（地基與協定編譯）**：建立共用 `proto/chat.proto`，配置後端 Maven 與前端 npm 的 Protobuf 自動編譯（codegen），並以單元測試驗證 Java 端 `byte[]` 序列化與反序列化。
- **Phase 1（HTTP REST + Protobuf 二進位收發）**：後端配置 `ProtobufHttpMessageConverter` 提供發送訊息與取得歷史訊息的 REST API（`application/x-protobuf`）；前端用 Vue 3 + `fetch` (`ArrayBuffer` / `Uint8Array`) 收發二進位封包。
- **Phase 2（WebSocket + Protobuf 即時通訊聊天室）**：後端使用 Spring WebSocket `BinaryWebSocketHandler` 收發 `BinaryMessage`；前端使用原生 `WebSocket` (`binaryType = "arraybuffer"`) 實作多分頁即時廣播聊天室（含加入/離開通知與一般訊息）。

### 明確不做（以及為什麼現在不做）

| 不做 | 為什麼 | 之後會做嗎 |
|---|---|---|
| 關聯式資料庫（PostgreSQL / MySQL）與持久化 | 資料庫配置與 ORM 與 Protobuf 通訊協定無關，會分散學習焦點 | 不會 |
| 帳號密碼註冊、登入與 JWT / Session 權限驗證 | 前端直接輸入暱稱即可識別發送者，省去認證流程 | 不會 |
| 多聊天室分房、私訊、檔案上傳、已讀回條 | 單一大廳聊天室已足夠完整演練 Protobuf 結構、enum 與 repeated 欄位 | 視學習需求可於 Phase 3 擴充（用來練習協定演進與向後相容） |
| Maven Wrapper (`mvnw`) | 本機已安裝全域 Maven 3.8.6，直接使用 `mvn` 指令即可 | 不會 |

### 現在就要留後路的

1. **`.proto` 檔案獨立於前後端目錄之外（根目錄 `proto/`）**：確保單一真實來源（Single Source of Truth），未來若新增其他語言客戶端或擴充 Phase 3 協定演進時，前後端都從同一個檔案重新 codegen。
2. **傳輸層封包預留擴充性（Envelope / MessageType）**：`ChatMessage` 內含 `MessageType` 枚舉（聊天、加入、離開）與欄位編號規範，讓 HTTP 與 WebSocket 能共用同一套 Protobuf 訊息結構，且未來新增欄位不破壞舊解析器。

---

## 架構決策

每條決策：**選了什麼、為什麼、排除了什麼、什麼情況下要重新評估。** 編號不重排，之後的報告與程式註解用「決策 N」引用。

### 決策 1：教學協作採用「報告含完整教學與程式碼藍圖 ＋ 使用者親手實作」

**選擇**：每次動工前，AI 先產出開工報告（`docs/plans/phase X/000NNN - ....md`），報告中除了四項必備外，必須包含**套件核心功能教學、必記要點、以及每個步驟的完整參考程式碼**。報告核准後，**由使用者親手建立檔案與撰寫程式碼**；使用者遇到疑問隨時透過 `@ask` / `@but` 或對話提問，寫完後交由 AI 協助驗證與記錄結果。

**理由**：使用者明確希望透過親手實作掌握每個環節，並深入了解每個套件的常用功能與必記要點。將教學與程式碼藍圖寫進報告，既能避開 CLI 終端機閱讀長文的痛苦，又能保留「先評估、可討論、有紀錄」的開發流程優點。

**排除**：
- AI 核准後自動寫完所有程式碼 —— 使用者少掉親手敲鍵盤與除錯的體感，不符合學習目的。
- 只在 CLI 對話視窗貼教學 —— 終端機不適合閱讀長篇程式碼與套件解析，且無法用 `@ask` / `@but` 精確標註段落。

**重新評估的時機**：若某步驟純屬重複性樣板（如純 CSS 樣式）且使用者明確要求「這步幫我直接寫」，才改由 AI 寫入。

### 決策 2：通訊傳輸分兩階段演進（先 HTTP `application/x-protobuf`，後 WebSocket `BinaryMessage`）

**選擇**：Phase 1 先用 Spring MVC REST Controller + `ProtobufHttpMessageConverter` 走 HTTP 二進位請求/回應；Phase 2 再加上 Spring WebSocket `Text/Binary` 中的 `BinaryWebSocketHandler` 走長連線二進位幀，兩階段共用同一個 `chat.proto` 與記憶體訊息儲存庫。

**理由**：HTTP 請求/回應模型最單純，能在瀏覽器 DevTools 的 Network 分頁清楚觀察 Request Headers (`Content-Type: application/x-protobuf`)、Response Payload 大小與二進位內容，最適合建立對 Protobuf 序列化的第一印象；掌握編解碼後再進階到 WebSocket 雙向推播，學習曲線最平緩。

**排除**：
- 直接上 WebSocket —— 同時面對連線生命週期管理與二進位編解碼，出錯時不易區分是 WebSocket 設定問題還是 Protobuf 編碼問題。
- 使用 gRPC / gRPC-Web —— 瀏覽器端無法直接連原生 gRPC（HTTP/2 framing 限制），必須額外架設 Envoy Proxy 或特殊轉發層，違反「0 外部容器」的硬約束，且遮蔽了底層 `byte[]` / `Uint8Array` 的序列化過程。

**重新評估的時機**：Phase 2 完成後，若想另外比較 gRPC-Web 與純 Protobuf over HTTP/WS 的差異，可另開實驗分支。

### 決策 3：後端建置與 Protobuf 編譯選用 Maven + `protobuf-java` + `protoc-jar-maven-plugin`

**選擇**：後端採用 Spring Boot 3.4.x + Maven（直接使用本機 `mvn`），依賴官方執行期套件 `com.google.protobuf:protobuf-java`，並使用 `com.github.os72:protoc-jar-maven-plugin` 在 `mvn generate-sources` / `mvn compile` 階段自動下載對應平台的 `protoc` 編譯 `../proto/chat.proto`。Java 編譯目標設定為 `<maven.compiler.release>21</maven.compiler.release>`。

**理由**：
1. `protoc-jar-maven-plugin` 內建自動抓取對應作業系統（Windows amd64）的 `protoc` 執行檔，不需要手動去 GitHub 下載 `protoc.exe` 並設定 Windows PATH，也不需要複雜的 OS detector 設定。
2. 本機安裝的是最新的 **JDK 26**，而目前主流 Spring Boot 3.x 內建的 ASM / ByteBuddy 對過新的 class file version 可能產生相容性問題，透過 `<maven.compiler.release>21</maven.compiler.release>` 鎖定編譯目標版本為 Java 21 LTS，可確保在 JDK 26 下穩定建置與執行。

**排除**：
- 手動安裝 `protoc.exe` 並手敲指令產生 Java 檔 —— 容易忘記重跑、無法整合進 Maven 建置生命週期。
- `xolstice/protobuf-maven-plugin` —— 需額外搭配 `os-maven-plugin` 擴充且設定較繁瑣。

**重新評估的時機**：若 `protoc-jar-maven-plugin` 在特定版本下載緩慢，可改用 Maven Central 直接拉取 `com.google.protobuf:protoc` binary artifact 的設定方式。

### 決策 4：前端採用 Vue 3 (Vite + TypeScript) + `protobufjs` 靜態程式碼生成（Static Codegen）

**選擇**：前端使用 Vite 建立 `vue-ts` 專案，安裝 `protobufjs`（執行期）與 `protobufjs-cli`（開發期 CLI：`pbjs` 與 `pbts`）。在 `package.json` 配置 `npm run proto` 指令，將 `../proto/chat.proto` 預先編譯為 `src/proto/chat.js` 與 `src/proto/chat.d.ts` 靜態模組。

**理由**：
1. **API 最直觀且貼近底層**：`ChatMessage.create({...})` 建立物件、`ChatMessage.encode(msg).finish()` 產出 `Uint8Array`、`ChatMessage.decode(uint8Array)` 還原物件，能清楚看見二進位 `Uint8Array` 的轉換過程。
2. **具備完整 TypeScript 型別提示**：透過 `pbts` 產生的 `.d.ts`，在 Vue 元件中存取欄位或 `MessageType` 枚舉時有完整 IDE 自動補全與編譯期檢查。
3. **純前端零外部依賴**：`pbjs` 與 `pbts` 純 Node.js 實作，不需要系統安裝 `protoc.exe` 就能直接在前端編譯 `.proto`。

**排除**：
- `protobufjs` 動態載入（Runtime `protobuf.load("chat.proto")`）—— 執行期才發 HTTP 請求去抓 `.proto` 檔解析，沒有 TypeScript 靜態型別檢查，不符合實務生產環境做法。
- Google 官方舊版 `google-protobuf` —— 產出為舊式 Closure Compiler 風格的 CommonJS 程式碼，對 Vite (ESM) 與 TypeScript 極不友善。

**重新評估的時機**：無（此規模下 `protobufjs` static module 為最穩定輕量的選擇）。

### 決策 5：後端訊息儲存採用執行緒安全的記憶體結構（Bounded In-Memory Store）

**選擇**：後端以 Spring `@Service` 封裝一個 `ConcurrentLinkedDeque<ChatMessage>`（或搭配讀寫鎖），寫入時若超過 1,000 則上限則移除最舊訊息；伺服器提供的新訊息 ID 由 `AtomicLong` 單調遞增序號產生，時間戳由伺服器端 `System.currentTimeMillis()` 填入。

**理由**：
1. 滿足「0 外部資料庫」約束，啟動即用。
2. 因為生成的 Protobuf Java 物件（`ChatMessage`）天生是**不可變的（Immutable）**，直接儲存在記憶體集合中在多執行緒環境下非常安全，正好能展示 Protobuf Java 物件「不可變設計（Builder 模式）」的優勢。
3. 序號與時間戳由伺服器統一分配，避免不同瀏覽器客戶端時鐘不一致造成排序錯亂。

**排除**：
- 接 H2 / SQLite 資料庫 —— 需要多寫 Entity 與 Protobuf Message 之間的轉換（Mapper），在學習初期增加不必要的雜訊。

**重新評估的時機**：無。

---

## 技術棧

| 層 | 技術 | 依據 |
|---|---|---|
| 通訊協定定義 | Protocol Buffers v3 (`proto3`) | 硬約束、決策 2 |
| 後端語言與執行環境 | Java (本機 JDK 26，編譯目標 `--release 21`) + Maven 3.8.6 | 決策 3 |
| 後端框架 | Spring Boot 3.4.x (`spring-boot-starter-web`, `spring-boot-starter-websocket`) | 決策 2、決策 3 |
| 後端 Protobuf 工具鏈 | `com.google.protobuf:protobuf-java` (4.x / 3.25.x) + `protoc-jar-maven-plugin` | 決策 3 |
| 前端框架 | Vue 3 + TypeScript + Vite (Node.js v22.22.3) | 決策 4 |
| 前端 Protobuf 工具鏈 | `protobufjs` + `protobufjs-cli` (`pbjs` / `pbts`) | 決策 4 |
| 資料儲存 | JVM 記憶體內 `ConcurrentLinkedDeque<ChatMessage>`（上限 1,000 筆） | 硬約束、決策 5 |

---

## 階段路線圖

每階段的**完成條件要能驗證**。「功能做完」不是完成條件。

| 階段 | 目標 | 完成條件 |
|---|---|---|
| Phase 0 | 地基：`.proto` 定義、前後端專案骨架與自動 Codegen、Java 序列化單元測試 | 1. `mvn test-compile` 自動從 `proto/chat.proto` 產出 Java 類別且通過序列化單元測試（物件 → `byte[]` → 物件欄位完全一致，且印出二進位 byte 長度與 JSON 字串長度對比）<br>2. 前端 `npm run proto` 成功產出 `chat.js` 與 `chat.d.ts` 且 `npm run build` 型別檢查通過 |
| Phase 1 | HTTP REST + Protobuf 二進位收發 | 1. 後端啟動於 `8080` 埠，`POST /api/messages` 與 `GET /api/messages` 僅收發 `application/x-protobuf`（搭配 Spring `MockMvc` 整合測試通過）<br>2. 前端啟動並能發送訊息與拉取歷史清單，瀏覽器 DevTools Network 面板確認 Request/Response Content-Type 為 `application/x-protobuf` 且 Payload 為二進位 |
| Phase 2 | WebSocket + Protobuf 即時通訊聊天室 | 1. 後端 WebSocket 端點 `/ws/chat` 支援 `BinaryMessage` 連線管理與廣播（含併發廣播與容量上限單元測試通過）<br>2. 開啟 2 個以上瀏覽器分頁輸入不同暱稱，任一分頁發送訊息、加入或離開時，其餘分頁在 **< 100 ms** 內即時收到二進位幀並解碼渲染於畫面上 |

每個階段開始時先寫一份「任務拆解」報告（見 `.claude/skills/plan-report/`）。

---

## 待驗證假設與風險

架構押在上面、但還沒證實的事。

| 假設 / 風險 | 錯了的話 | 怎麼驗 | 在哪一階段驗 |
|---|---|---|---|
| 本機 JDK 26 搭配 Maven `<release>21</release>` 可正常編譯並啟動 Spring Boot 3.4.x | 若 Maven Compiler Plugin 或 Spring Boot ASM 在 JDK 26 報錯，需升級 `maven-compiler-plugin` 版本或切換本機 JDK 版本 | 在 Phase 0 執行 `mvn test` 跑通第一個 JUnit 測試 | Phase 0 |
| `protobufjs-cli` (`pbjs`) 產生的靜態模組在 Vite (ESM) 環境下的預設匯出格式可直接 `import` | CommonJS 與 ESM 互操作在 Vite 打包時可能報 `default export` 錯誤；若發生則改用 `pbjs -t static-module -w es6` 參數產出原生 ES6 模組 | 在 Phase 0 執行 `npm run proto` 與 `npm run build` 驗證打包無誤 | Phase 0 |
