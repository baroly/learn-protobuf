---
name: backend-style
description: 後端與 Protobuf 協定程式碼規範 —— 適用於修改 proto/*.proto 與 backend/ 下的 Java / Maven 檔案前載入。
---

# 後端與 Protobuf 協定規範（Java + Spring Boot + Protobuf）

適用於 `proto/*.proto` 與 `backend/` 目錄下的所有檔案。

---

## 註解

**原則：說明「作用」，不逐行複述程式碼。一律使用繁體中文。**

### 類別與函式上方：一到三行說明作用

講清楚這東西負責什麼、有什麼不直觀的保證或限制。**設計理由不寫在這裡**，放 `docs/architecture.md`，註解留一個指標：

```java
/** 負責儲存最近 1,000 則聊天訊息並分配單調遞增 ID。設計理由見 docs/architecture.md 決策 5。 */
```

### 並列的常數與值域（含 `.proto` 欄位與 enum）：逐行註解，不省

**這是「不逐行註解」的例外，而且是硬性要求。** `.proto` 裡的每個 `enum` 值、每個 `message` 欄位，每一項都要有自己的行尾註解並對齊成一欄，且在上方標註「欄位編號／枚舉數字不可重排」：

```protobuf
// 訊息類型枚舉（數字寫入二進位封包，不可重排）
enum MessageType {
  CHAT = 0;   // 一般使用者發送的聊天文字訊息（proto3 預設值）
  JOIN = 1;   // 使用者進入聊天室時的系統廣播通知
  LEAVE = 2;  // 使用者離開聊天室時的系統廣播通知
}
```

---

## 錯誤處理

一律使用 **Java Exception 搭配 Spring `@RestControllerAdvice` 統一轉譯層**。

- 業務與儲存層只管拋出明確例外（如 `IllegalArgumentException`），不依賴 HTTP 狀態碼。
- Protobuf 二進位反序列化失敗（`InvalidProtocolBufferException`）由 Spring `ProtobufHttpMessageConverter` 或 WebSocket Handler 捕捉並記錄 `WARN`，不將內部 stack trace 原樣回傳給客戶端。
- 絕不寫空的 `catch` 區塊；包裝例外時永遠保留 `cause`。

---

## 日誌

使用 SLF4J 參數化寫法（`log.debug("Received message id={}", msg.getId())`），禁止字串串接。

- `ERROR`：系統級異常
- `WARN`：客戶端傳來無法解析的二進位封包、WebSocket 非預期斷線
- `INFO`：伺服器啟動、WebSocket 連線建立／關閉
- `DEBUG`：每則訊息的收發細節（**不進 INFO，且不直接印出敏感個資**）

---

## 協定與 API 合約（`proto/chat.proto`）

**改 `.proto` 協定定義是架構改動，依 `CLAUDE.md` 要先報告。**

| 可以 | 不可以 |
|---|---|
| 加新欄位（使用新編號）、新的 enum 值 | 改既有欄位的編號（Tag Number）、型別 |
| | 刪掉欄位後重用它的編號或名字 |

`backend/target/generated-sources/` 為產生碼，**不進版控、不手改**；修改 `proto/chat.proto` 後重跑 `mvn compile`。

---

## Java 與 Spring 特有慣例

1. **Protobuf 物件不可變（Immutable）**：一律透過 `ChatMessage.newBuilder()...build()` 建立；若需基於收到物件補充伺服器端 `id` 與 `timestamp`，使用 `incomingMessage.toBuilder().setId(...).setTimestamp(...).build()`。
2. **建置注入**：Spring 元件一律使用**建構子注入（Constructor Injection）**，不使用 `@Autowired` 欄位注入。
3. **JDK 版本**：專案統一使用 **JDK 21**（於 IDEA 選擇 JDK 21，`pom.xml` 設定 `<java.version>21</java.version>`）。

---

## 完成之前

依 `CLAUDE.md`：**建置通過，且相關測試通過**才算完成。

```bash
cd backend
mvn clean test
```
