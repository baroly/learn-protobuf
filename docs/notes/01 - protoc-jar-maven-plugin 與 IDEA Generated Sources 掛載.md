# protoc-jar-maven-plugin 與 IntelliJ IDEA Generated Sources 掛載問題

## 症狀

在 Maven `pom.xml` 配置 `com.github.os72:protoc-jar-maven-plugin` 後：
1. 點擊 IDEA 上方的綠色小錘子（Build Project）不會產生任何 `.java` 檔。
2. 執行 Maven `compile` 產生檔案後，IDEA 編輯器在測試或主程式中仍然無法自動補全或 `import` 產生的 Protobuf Java 類別（或者修改 `.proto` 新增/改名 Message 後，IDEA 抓不到新類別）。

---

## 根因分析

1. **預設輸出目錄與 Lombok `annotations` 衝突**：
   - `protoc-jar-maven-plugin` 若未指定 `<outputDirectory>`，預設直接輸出在 `target/generated-sources/` 根目錄。
   - 若專案同時使用 Lombok 或其他 Annotation Processor，IDEA 會將子目錄 `target/generated-sources/annotations` 標記為 `Generated Sources Root`。
   - IntelliJ IDEA 的模組模型**不允許父目錄與子目錄同時作為 Source Root**，因此父目錄 `target/generated-sources` 會被 IDEA 忽略。
2. **IDEA 的 `Reload All Maven Projects` 不會主動執行 `generate-sources` phase**：
   - `protoc-jar-maven-plugin` 是在 `run` goal 實際執行時，才會動態呼叫 Maven API `project.addCompileSourceRoot(...)` 把輸出目錄註冊進 Maven 模型。
   - 若只在 IDEA 點擊「Reload All Maven Projects」，IDEA 僅靜態讀取 `pom.xml`，不會觸發 `protoc-jar:run`，因此不知道 `target/generated-sources/protobuf` 是原始碼目錄。

---

## 解法

1. **在 `pom.xml` 明確指定獨立子目錄 `<outputDirectory>`**：
   ```xml
   <configuration>
       <protocArtifact>com.google.protobuf:protoc:${protobuf.version}</protocArtifact>
       <outputDirectory>${project.build.directory}/generated-sources/protobuf</outputDirectory>
       <inputDirectories>
           <include>${project.basedir}/../proto/demo/chat</include>
       </inputDirectories>
   </configuration>
   ```
2. **在 IDEA 中讓資料夾變為藍色 Generated Sources Root（二選一）**：
   - **方法 A（Maven 面板）**：點擊 Maven 面板左上角第 2 個按鈕 **「Generate Sources and Update Folders For All Projects」**（資料夾加循環箭頭圖示，在 Reload 按鈕旁邊）。
   - **方法 B（手動標記，最直接）**：在左側專案樹展開 `demo/target/generated-sources/protobuf`，對 `protobuf` 資料夾按右鍵 → **`Mark Directory as`** → **`Generated Sources Root`**。
