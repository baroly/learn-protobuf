# 08 - Phase 0 至 Phase 2 Protobuf 前後端二進位通訊總複習與進階語法預覽 (`oneof` 與 `map`)

這份筆記濃縮了我們在 **Phase 0（地基與 Codegen）**、**Phase 1（HTTP REST 二進位收發）** 與 **Phase 2（WebSocket 即時二進位廣播）** 親手實作過的全部核心觀念，並在後半部預覽 **Phase 3 即將挑戰的三大進階主題：向後相容、`oneof` 多態封包、以及 `map<K, V>` 鍵值對**。

---

## 第一部分：Phase 0 ～ Phase 2 核心重點總複習

### 1. 底層二進位 Wire Format 三大鐵律（為什麼 Protobuf 比 JSON 小 60% 以上？）

1. **二進位封包裡「沒有欄位名稱」，只有「欄位編號（Field Number）＋型別（Wire Type）」**：
   - JSON 每次傳輸都要重複把 `"sender"`、`"timestamp"` 這些英文字母轉成 UTF-8 bytes 傳出去。
   - Protobuf 把 `欄位編號 (field_number)` 與 `型別代號 (wire_type)` 擠進 **1 個 Byte 的 Tag** 裡：`Tag = (field_number << 3) | wire_type`。
   - **必記鐵律**：`.proto` 裡的欄位名稱（如 `sender`）改名不會影響二進位傳輸；但**欄位編號（`= 1, 2...`）一旦上線絕對不可偷改或重排**！
2. **兩種核心編碼結構（TV vs TLV）**：
   - **TV（Tag - Value）**：用於 `int32`、`int64`、`bool`、`enum`（Wire Type = `0`，採用 **Varint 變長整數**，數字越小佔用越少 byte，例如 `enum CHAT = 0` 預設值甚至直接省略不傳，佔 `0 byte`！）。
   - **TLV（Tag - Length - Value）**：用於 `string`、`bytes`、嵌套 `Message`、`repeated`（Wire Type = `2`，先寫 Tag、再寫 byte 長度 Length、最後寫實際內容 Value）。
3. **單一真實來源（Single Source of Truth）**：
   - 前後端絕不手寫 DTO 類別，一律修改根目錄的 `proto/demo/chat/chat.proto`，再由 Maven (`protoc-jar-maven-plugin`) 與 npm (`pbjs` + `pbts`) 自動產生 Java 與 TypeScript 程式碼。

---

### 2. 前後端 Protobuf API 對照速查表（背這張表就夠了！）

| 操作環節 | 後端 Java (`com.google.protobuf`) | 前端 TypeScript (`protobufjs` 靜態模組) | 必記陷阱與要點 |
|---|---|---|---|
| **1. 建立訊息物件** | `ChatMessage.newBuilder()`<br>`.setSender("Leo")`<br>`.build()` | `ChatMessage.create({`<br>`  sender: 'Leo'`<br>`})` | Java 產出的 `ChatMessage` 是**完全不可變（Immutable）**的，修改既有物件必須用 `msg.toBuilder().setId(...).build()` |
| **2. 序列化（物件 ➔ 二進位）** | `byte[] bytes = msg.toByteArray();` | `const bytes: Uint8Array =`<br>`  ChatMessage.encode(msg).finish()` | 前端記得一定要加 **`.finish()`** 才會從 `Writer` 拿到 `Uint8Array`！ |
| **3. 反序列化（二進位 ➔ 物件）** | `ChatMessage msg =`<br>`  ChatMessage.parseFrom(bytes);` | `const msg =`<br>`  ChatMessage.decode(uint8Array)` | Java 解析非法二進位會拋出受檢例外 `InvalidProtocolBufferException`（HTTP 回 400、WS 記錄警告並忽略） |
| **4. HTTP REST 收發** | Spring 註冊 `@Bean ProtobufHttpMessageConverter`<br>Controller 標註 `produces / consumes = "application/x-protobuf"` | `fetch('/api/messages', {`<br>`  headers: { 'Content-Type': 'application/x-protobuf' },`<br>`  body: new Uint8Array(bytes)`<br>`})`<br>接收：`new Uint8Array(await res.arrayBuffer())` | **千萬別傳 `bytes.buffer`**：`protobufjs` 底層共用 8KB `ArrayBuffer` 記憶體池，傳 `.buffer` 會把整塊 8192 bytes 垃圾資料送出去！ |
| **5. WebSocket 即時收發** | 繼承 `BinaryWebSocketHandler`<br>收：`ChatMessage.parseFrom(message.getPayload())`<br>發：`session.sendMessage(new BinaryMessage(bytes))` | `const ws = new WebSocket(url)`<br>**`ws.binaryType = 'arraybuffer'`**<br>發：`ws.send(new Uint8Array(bytes))`<br>收：`new Uint8Array(event.data)` | 1. 前端沒設 `binaryType = 'arraybuffer'` 時預設是 `'blob'`，直接轉 `Uint8Array` 會變成 `0 bytes`！<br>2. 後端多執行緒對同一 `session` 發送時必須 `synchronized (session)` |

---

### 3. 後端併發與單元測試三寶（Phase 1 & Phase 2 實戰精華）

1. **`ConcurrentLinkedDeque` + `AtomicLong`（無鎖定高併發儲存庫）**：
   - 用 `AtomicLong.incrementAndGet()` 產生絕對不重複的遞增序號。
   - 用 `while (deque.size() > 1000) { deque.pollFirst(); }` 處理多執行緒瞬間同時塞入時的容量淘汰，避免 `if` 只淘汰一筆的競態條件。
2. **`CountDownLatch` 雙門閂（起跑槍 + 終點線）**：
   - `startLatch = new CountDownLatch(1)` 讓 10 個執行緒在起跑線同時 `await()` 待命，主執行緒一喊 `countDown()` 瞬間達成真正的同時搶鎖。
   - `doneLatch = new CountDownLatch(10)` 讓主執行緒等待全部子執行緒跑完再斷言。
3. **Mockito 三劍客（`mock` 替身、`verify` 監視器、`ArgumentCaptor` 捕手手套）**：
   - 不必啟動整座 Tomcat 伺服器，直接 `mock(WebSocketSession.class)` 模擬假連線，並用 `ArgumentCaptor.forClass(BinaryMessage.class)` 攔截後端廣播出去的二進位封包來解碼驗貨。

---

## 第二部分：Phase 3 進階主題預覽（回答你的問題！）

你在對話中問到：「**1. 向後兼容、2. 多態封包、以及 `map<string, string>` 是什麼？**」
這三個正好是把 Protobuf 從「玩具專案」提升到「工業級生產環境」最關鍵的三塊拼圖！我們用最白話的方式一次講清楚：

---

### 拼圖 A：`map<string, string>` 是什麼？（Protobuf 的鍵值對字典）

#### 1. 一句話秒懂
它就是 **Java 裡的 `Map<String, String>`**（或 **JavaScript / TypeScript 裡的普通物件 `{ [key: string]: string }`**）！

#### 2. 為什麼我們需要它？
回想一下我們在 `chat.proto` 學過的型別：
- 單一值：`string sender = 2;`（只能放 1 個字串）
- 列表陣列：`repeated ChatMessage messages = 1;`（相當於 Java 的 `List<ChatMessage>`，有順序的陣列）

但如果今天你想在每則聊天訊息上附帶**一組不固定數量的「鍵值對屬性（Key-Value Pairs）」**，例如：
- **情境 1（客戶端環境資訊 `metadata`）**：你想知道這則訊息是從哪個瀏覽器、哪個作業系統發出来的，例如 `{"browser": "Chrome", "os": "Windows 11", "clientVer": "2.0"}`。
- **情境 2（表情符號按讚統計 `reactions`）**：你想記錄這則訊息被按了幾個讚、幾個愛心，例如 `{"👍": 5, "❤️": 3}`（這時可以寫 `map<string, int32> reactions`）。

如果在沒有 `map` 的年代，你得自己傻傻定義一個 `message KeyValuePair { string key = 1; string value = 2; }` 然後寫 `repeated KeyValuePair`。現在 `proto3` 直接內建了 **`map<KeyType, ValueType>`** 語法！

#### 3. 在 `.proto`、Java 與 TypeScript 裡長什麼樣？

**① 在 `chat.proto` 裡宣告：**
```protobuf
message ChatMessage {
  string id = 1;
  string sender = 2;
  string content = 3;
  int64 timestamp = 4;
  MessageType type = 5;

  // ★ 新增第 6 號欄位：字串對字串的 Map 字典！
  map<string, string> metadata = 6;
}
```

**② 在後端 Java 裡怎麼用？（`protoc` 自動幫你生成 `putXxx` 與 `getXxxMap`）**
```java
// 塞入鍵值對（就像操作一般 Map 一樣直覺！）
ChatMessage msg = ChatMessage.newBuilder()
    .setSender("Leo")
    .setContent("Hello Map!")
    .putMetadata("client", "web-vue3")
    .putMetadata("theme", "light-blue")
    .build();

// 讀取 Map（回傳不可變的 java.util.Map<String, String>）
Map<String, String> meta = msg.getMetadataMap();
String client = msg.getMetadataOrDefault("client", "unknown"); // "web-vue3"
```

**③ 在前端 TypeScript 裡怎麼用？（直接傳 JS 物件 `{ key: value }`！）**
```ts
const msg = ChatMessage.create({
  sender: 'Leo',
  content: 'Hello Map!',
  metadata: {
    client: 'web-vue3',
    theme: 'light-blue',
  },
})
```
> 💡 **必記小知識**：Protobuf 的 `map<K, V>` 中，**Key (`K`)** 可以是任何整數（`int32`, `int64`）、`bool` 或 `string`；而 **Value (`V`)** 可以是任何型別（包含另一個自訂的 `Message`！）。此外，跟 Java `HashMap` 一樣，**`map` 裡面的鍵值對是沒有固定順序的**。

---

### 拼圖 B：`oneof` 多態封包是什麼？（多選一的互斥抽屜）

#### 1. 痛點情境：當聊天室不只傳「純文字」時怎麼辦？
現在我們的 `ChatMessage` 只有一個 `string content = 3;`。
但如果聊天室升級了，一則訊息可能是：
1. **純文字訊息**（只需要 `string text`）
2. **貼圖訊息（Sticker）**（需要 `string pack_id` 貼圖包代號 + `string sticker_code` 貼圖編號）
3. **轉帳／紅包訊息**（需要 `int32 amount` 金額 + `string note` 祝福語）

如果你把這些欄位全部平鋪塞進 `ChatMessage`，不僅容易搞混「這則訊息到底是貼圖還是文字？」，萬一有人同時填了文字又填了貼圖，接收端就不知道該渲染哪一個了。

#### 2. `oneof` 的魔法：編譯期與執行期強制「多選一」
在 `.proto` 中使用 **`oneof`** 把這幾個互斥的欄位包起來：
```protobuf
message StickerPayload {
  string pack_id = 1;      // 例如 "capoo"
  string sticker_code = 2; // 例如 "wave" (揮手貼圖)
}

message ChatMessage {
  string id = 1;
  string sender = 2;
  string content = 3;
  int64 timestamp = 4;
  MessageType type = 5;
  map<string, string> metadata = 6;

  // ★ 多態封包核心：payload 裡面同一時間「只能存在其中一種」！
  oneof payload {
    string rich_text = 7;          // 選項 A：富文本/文字內容
    StickerPayload sticker = 8;    // 選項 B：貼圖結構
    string system_notice = 9;      // 選項 C：系統公告
  }
}
```
- **自動互斥覆蓋**：如果你在 Builder 先呼叫 `.setRichText("Hi")`、接著又呼叫 `.setSticker(sticker)`，Protobuf 會**自動把前面的 `rich_text` 清空**，確保二進位封包裡永遠只有最後設定的那一個！
- **天生支援 `switch` 型別判斷**：
  - 在 Java 中，`msg.getPayloadCase()` 會回傳枚舉（`RICH_TEXT`、`STICKER`、`SYSTEM_NOTICE` 或 `PAYLOAD_NOT_SET`）。
  - 在 TypeScript 中，`decodedMsg.payload` 會直接告訴你字串名稱 `'richText' | 'sticker' | 'systemNotice'`！

---

### 拼圖 C：向後相容與向前相容（Schema Evolution & `reserved`）

為什麼大公司（Google、Netflix、LINE）微服務與手機 App 升級時都不怕舊版 App 閃退？因為 Protobuf 的二進位 Wire Format 天生支援**雙向相容**：

1. **向後相容（Backward Compatibility — 新程式碼讀舊資料）**：
   - 當我們在 Phase 3 把 `chat.proto` 升級為 v2（多了 `6` 號 `metadata` 和 `7, 8, 9` 號 `oneof payload`），**舊的訊息（只有 1～5 號欄位）** 丟給新版程式解析時，6～9 號欄位會自動拿到安全的預設空值，100% 不會報錯。
2. **向前相容（Forward Compatibility — 舊程式碼讀新資料）**：
   - 如果**新客戶端**傳了帶有 `6, 8` 號欄位的新二進位封包給**還沒更新的舊解析器（只認識 1～5 號欄位）**，舊解析器看到不認識的 Tag 編號 `6` 和 `8`，會根據 Wire Type 的長度**安全跳過（並暫存在 `UnknownFieldSet` 中）**，照樣成功讀出 `1～5` 號欄位！
3. **`reserved` 防呆鎖（刪除欄位時的保命符）**：
   - 萬一有一天某個舊欄位確定廢棄不用了，**絕對不能直接把編號給新欄位重用**！必須在 `.proto` 寫上：
     ```protobuf
     reserved 99;
     reserved "legacy_field";
     ```
     這樣如果有同事不小心寫了 `string new_field = 99;`，`protoc` 編譯器會在編譯階段直接報錯攔截，徹底杜絕二進位資料錯位！
