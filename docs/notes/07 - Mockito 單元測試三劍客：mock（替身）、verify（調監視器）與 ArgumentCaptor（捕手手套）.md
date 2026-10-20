# 07 - Mockito 單元測試三劍客：`mock`（替身）、`verify`（調監視器）與 `ArgumentCaptor`（捕手手套）

在撰寫像 WebSocket Handler 這樣**沒有回傳值（`void`）**、依靠「呼叫外部連線物件（`WebSocketSession`）的方法」來完成工作的元件時，傳統的 `assertEquals(預期回傳值, 實際回傳值)` 派不上用場。這時就需要使用 **Mockito** 框架的三個核心工具。

---

## 一、`mock(Class)` 與 `when(...).thenReturn(...)` —— 派出裝滿監視器的替身演員

在單元測試中，我們不會真的啟動瀏覽器建立 TCP WebSocket 連線，而是用 `mock()` 捏造一個假的 `WebSocketSession` 替身：

```java
WebSocketSession session = mock(WebSocketSession.class);
when(session.getId()).thenReturn("session-A");
when(session.isOpen()).thenReturn(true);
```

- **`mock(WebSocketSession.class)`**：產生一個身上裝滿「針孔攝影機（監視器）」的假連線物件。任何人對它呼叫任何方法（例如 `sendMessage(...)` 或 `close(...)`），它都會把「呼叫了幾次、傳了什麼參數」完整記錄下來。
- **`when(...).thenReturn(...)`**：發給替身演員的「台詞劇本」—— 當受測程式問它 `session.isOpen()` 時，固定回答 `true`。

---

## 二、`verify(替身, 次數).方法(參數匹配)` —— 事後調閱監視器畫面

當受測方法（例如 `handler.handleMessage(...)`）執行完畢後，我們用 `verify` 調閱替身身上的監視器，確認受測程式剛剛有沒有對替身做出預期的動作。

`verify` 括號裡的參數檢查分為 **3 種模式**，決定了後面需不需要再寫 `assert`：

| 模式 | 語法範例 | `verify` 做了什麼？ | 後面還要寫 `assert` 嗎？ |
|---|---|---|---|
| **1. 具體值比對** | `verify(clientA, times(1)).close(CloseStatus.NORMAL);` | 1. 檢查 `close` 被呼叫 1 次<br>2. **自動用 `.equals()` 比對**實際傳入參數是否完全等於 `CloseStatus.NORMAL` | ❌ **不用**（`verify` 自己已經驗完貨了） |
| **2. 模糊匹配器 (`any`)** | `verify(clientB, times(1)).sendMessage(any(BinaryMessage.class));` | 只檢查 `sendMessage` 被呼叫 1 次，且傳入的是任意 `BinaryMessage` 實例（不在乎裡面的內容） | ❌ **不用**（本來就不打算驗內容細節） |
| **3. 捕手手套 (`ArgumentCaptor`)** | `verify(clientA, times(1)).sendMessage(captor.capture());` | 只檢查 `sendMessage` 被呼叫 1 次，並**把當時傳入的參數抓進 `captor` 手套裡**（此時尚未檢查參數內部欄位！） | ✅ **一定要！** 必須取出 `captor.getValue()` 並用 `assertEquals` 拆箱驗貨 |

---

## 三、什麼時候非用 `ArgumentCaptor` 不可？

有兩種最常見的實戰情境（正好對應 `ChatWebSocketHandlerTest` 的測試 1 與測試 4）：

### 情境 1：參數裡面含有動態產生的時間戳或壓縮二進位（測試 1）
`ChatWebSocketHandler` 廣播出去的 `BinaryMessage` 裡包著 Protobuf `byte[]`，且內含當下毫秒數 `System.currentTimeMillis()`。測試程式不可能事先猜中幾毫秒後的時間戳來構造一個一模一樣的 `BinaryMessage` 放進 `verify()`。
因此必須先用 `frameCaptor.capture()` 把 `BinaryMessage` 攔截下來，反序列化成 `ChatMessage` 後，再針對個別欄位 `assertEquals("msg-1", msg.getId())`。

### 情境 2：只想驗證物件的「某個關鍵屬性」，不想被次要字串綁死（測試 4）
Spring 的 `BinaryWebSocketHandler` 在拒絕文字訊息時，內部呼叫的是：
```java
session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Text messages not supported"));
```
如果直接寫 `verify(clientA).close(CloseStatus.NOT_ACCEPTABLE)`，會因為 `reason` 欄位一個是 `null`、一個是 `"Text messages not supported"` 而導致 `.equals()` 失敗。
改用 `ArgumentCaptor<CloseStatus>` 把 `CloseStatus` 抓出來，只針對標準狀態碼驗證：
```java
ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
verify(clientA, times(1)).close(captor.capture());
assertEquals(CloseStatus.NOT_ACCEPTABLE.getCode(), captor.getValue().getCode()); // 只驗 1003 狀態碼！
```
這樣即使未來 Spring 升級改掉了英文提示文字，測試依然穩健通過！
