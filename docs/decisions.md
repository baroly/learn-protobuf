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
