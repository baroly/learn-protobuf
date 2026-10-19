# 決策偏離記錄

實作過程中偏離原計畫的決定，**追加不覆寫**。

`docs/architecture.md` 記錄的是「當初打算怎麼做」，這份記錄的是「實際上為什麼改成別的做法」。兩者一起讀才是完整的歷史。

從 `architecture.md` 核准那天開始記（kickoff 期間的修改直接改 `architecture.md`，不算偏離）。

**新條目加在最上面。** 格式：

```markdown
## YYYY-MM-DD · <一句話標題>

**原計畫**（`000NNN` / 決策 N）：<…>

**改成**：<…>

**為什麼**：<…>

**誰決定的**：<使用者 / 實作中發現、使用者同意>
```

討論一有結論就直接寫進來，不需要事先問。

---

## 2026-10-19 · 前端 UI 畫面與 CSS 樣式交由 AI 代寫（採用 `#4596FF` 淺藍色主題），使用者專注親手撰寫核心通訊邏輯

**原計畫**（決策 1 / `000004`）：前端所有檔案（含 `App.vue` 畫面模板與 CSS 樣式）皆由使用者親手撰寫。

**改成**：
1. 凡涉及**核心功能與通訊協定**的程式碼（如 `vite.config.ts` 代理設定、`src/api/chatApi.ts` 的 `fetch` + Protobuf `Uint8Array` / `ArrayBuffer` 編解碼封裝、Phase 2 的 WebSocket 封裝），維持 **100% 由使用者親手撰寫**。
2. 凡屬**純 UI 畫面排版與 CSS 樣式**（`src/style.css`、`src/App.vue` 視覺介面），依 `docs/architecture.md` 決策 1 的重新評估條件，由 **AI 直接代寫**，配色與版面風格對齊 `crypt-fiction` 專案的淺藍色系（主色 `--primary-color: #4596FF`、淺灰底 `#f5f5f5`、白底卡片 `#ffffff`、次要區塊 `#f9f9f9` 與 `4px` 左側主題色強調線）。

**為什麼**：使用者希望將時間與精力 100% 集中在學習 Protobuf 前後端二進位通訊功能開發，避免在 HTML/CSS 視覺排版樣板上耗費時間。

**誰決定的**：使用者

---

## 2026-10-19 · 後端統一於 `application.yaml` 設定 `context-path: /api`，Controller 路由簡化為 `@RequestMapping("messages")` 並採用 Lombok `@Slf4j`

**原計畫**（`000005`）：在 `ChatController` 直接寫 `@RequestMapping("/api/messages")`，日誌使用手動宣告 `LoggerFactory.getLogger(...)`。

**改成**：
1. 在 `demo/src/main/resources/application.yaml` 設定全域 `server.servlet.context-path: /api`，`ChatController` 路由寫 `@RequestMapping("messages")`（正式啟動時對外完整路徑仍為 `/api/messages`；`@WebMvcTest` 的 `MockMvc` 測試路徑則為 `"/messages"`）。
2. `ChatController` 與 `GlobalExceptionHandler` 使用專案已引入的 Lombok `@Slf4j` 取代手動宣告 `Logger`。

**為什麼**：透過 `context-path: /api` 統一管理所有後端 REST 端點的前綴，避免每個 Controller 重複寫 `/api`；使用 `@Slf4j` 讓程式碼更精簡。

**誰決定的**：使用者實作時定案

---

## 2026-10-16 · 後端目錄命名為 `demo/`、Proto 路徑為 `proto/demo/chat/chat.proto`、Java package 為 `com.demo.chat`

**原計畫**（`000002`）：後端目錄為 `backend/`、Proto 路徑為 `proto/chat.proto`、Java package 為 `com.example.chat`；`protoc-jar-maven-plugin` 使用預設輸出路徑。

**改成**：
1. 後端 Maven 專案目錄為 `demo/`，基礎 package 為 `com.demo.chat`（產生碼 package 為 `com.demo.chat.proto`）。
2. Proto 檔案路徑為 `proto/demo/chat/chat.proto`（Protobuf package 為 `demo.chat`）。
3. `protoc-jar-maven-plugin` 明確加上 `<outputDirectory>${project.build.directory}/generated-sources/protobuf</outputDirectory>`。

**為什麼**：使用者以 IDEA Spring Initializr 建立 `demo` 模組並自訂 package 結構；同時為避免 `target/generated-sources` 與 Lombok 的 `target/generated-sources/annotations` 產生父子目錄 Source Root 衝突，將 Protobuf 產生碼輸出至獨立的 `protobuf` 子目錄。

**誰決定的**：使用者實作時定案、實作中發現並修正

---

## 2026-10-16 · 後端開發與編譯環境直接使用 JDK 21

**原計畫**（`000001` / 決策 3）：本機預設環境為 JDK 26，透過 `<maven.compiler.release>21</maven.compiler.release>` 跨版本編譯以規避 Spring Boot 3.x 相容性風險。

**改成**：直接使用 **JDK 21**（或 JDK 22）作為專案開發與編譯的 JDK（透過 IntelliJ IDEA 的多 JDK 支援切換，避免使用 JDK 26 觸發 Lombok 1.18.36 `TypeTag :: UNKNOWN` 編譯錯誤）。

**為什麼**：IDEA 原生支援多版本 JDK 管理與切換，直接選用相容 JDK 可徹底消除 JDK 26 與 Lombok / Maven 插件的編譯衝突。

**誰決定的**：使用者（於核准 `000001` 時定案）
