# 000008 - 後端 Spring WebSocket 二進位處理器（`BinaryWebSocketHandler`）與併發廣播測試

**狀態**：已完成（2026-10-20）：後端 14 個測試（含 WebSocket 二進位廣播、1003 拒絕文字幀與 10 執行緒併發廣播）全綠通過
**日期**：2026-10-20
**前置**：`000007`（Phase 2 任務拆解）已核准、Phase 1（`000005` 後端 `ChatMessageStore` 與 `ChatController`）已完成。

這份報告為後端 `demo/` 加入 **Spring WebSocket 二進位全雙工廣播引擎**：
1. 在 `demo/pom.xml` 引入 `spring-boot-starter-websocket` 套件。
2. 實作 `ChatWebSocketHandler`（繼承 `BinaryWebSocketHandler`），負責管理所有連線中的瀏覽器分頁（`WebSocketSession`），當任一客戶端傳來 Protobuf 二進位幀（`BinaryMessage`）時，反序列化為 `ChatMessage`、寫入 `ChatMessageStore` 取得伺服器分配的 `msg-N` 與 `timestamp`，再將補齊後的 `ChatMessage` 轉成 `BinaryMessage` 即時廣播給所有連線中的客戶端。
3. 實作 `WebSocketConfig`（`@EnableWebSocket`），將 `ChatWebSocketHandler` 註冊至 `/ws/chat` 路由（搭配 `application.yaml` 的 `context-path: /api`，完整 WebSocket URL 為 `ws://localhost:8080/api/ws/chat`）。
4. 撰寫 `ChatWebSocketHandlerTest` 單元與併發廣播測試，驗證多客戶端連線管理、Protobuf `ByteBuffer` 解析、與 `ChatMessageStore` 的連動、10 執行緒同時發送訊息的併發廣播安全，以及拒絕非法二進位與純文字幀（`TextMessage`）。

做完之後：
- 在 `demo/` 執行 `mvn clean test`，原有的 9 個測試加上本份新增的 5 個 WebSocket 二進位測試（共 **14 個測試**）全部綠燈通過。
- 後端已具備 `/api/ws/chat` 二進位 WebSocket 端點，隨時準備好與下一份 `000009` 的前端進行多分頁即時廣播聯調。

---

## 零、先定 3 個結構問題

### 1. ★ 多執行緒同時對同一個 `WebSocketSession` 發送訊息的執行緒安全問題

- **問題**：
  在聊天室中，當分頁 A 與分頁 B **在同一微秒**發送訊息時，後端會有兩個 Tomcat 工作執行緒同時走進 `broadcast()` 迴圈，並**同時對分頁 C 的同一個 `WebSocketSession` 呼叫 `session.sendMessage(binaryMessage)`**。
  然而，標準 Jakarta WebSocket / Tomcat 的底層 `WsRemoteEndpointImplBase` **不是執行緒安全的**！如果兩個執行緒同時對同一個 `session` 呼叫 `sendMessage`，Tomcat 會直接拋出：
  `java.lang.IllegalStateException: The remote endpoint was in state [BINARY_FULL_WRITING] which is an invalid state for called method`！
- **選擇**：
  在 `ChatWebSocketHandler` 的 `afterConnectionEstablished` 建立連線時，統一使用 **Spring 內建的 `ConcurrentWebSocketSessionDecorator`** 將原始 `WebSocketSession` 包裝起來再放進連線池（設定發送逾時 `5,000 ms`、緩衝上限 `64 KB`），或是對單一 `session` 加鎖發送。本報告採用對單一 `session` 的 `synchronized (session)` 搭配 `ConcurrentHashMap.newKeySet()`（或 `ConcurrentWebSocketSessionDecorator`）—— 我們在藍圖中直接展示最直觀、零額外複雜度的 **`synchronized (session)` + `ConcurrentHashMap.newKeySet()`**（並在教學中一併介紹 `ConcurrentWebSocketSessionDecorator`）。
- **為什麼**：
  - `ConcurrentHashMap.newKeySet()` 保證「新增連線 / 移除斷線 / 遍歷所有連線」時永遠不會拋出 `ConcurrentModificationException`。
  - 發送時加上 `synchronized (session)` 保證**同一個客戶端分頁的 Socket 通道同一時間只會有一個執行緒在寫入 Frame**，而不同客戶端分頁之間依然可以平行發送互不阻塞！

### 2. ★ `BinaryMessage` 的 `ByteBuffer` 與 Protobuf `ChatMessage.parseFrom()` 零拷貝解析

- **問題**：
  Spring WebSocket 的 `BinaryMessage.getPayload()` 回傳的是 Java NIO 的 **`java.nio.ByteBuffer`**（而不是 `byte[]`）。如果先轉成 `byte[]` 再傳給 `ChatMessage.parseFrom(byte[])`，會多一次不必要的記憶體陣列複製。
- **選擇**：
  直接呼叫 Protobuf Java 原生支援的 **`ChatMessage.parseFrom(ByteBuffer data)`** 重載方法：`ChatMessage.parseFrom(message.getPayload())`。
- **為什麼**：
  `protobuf-java` 內建 `parseFrom(ByteBuffer)`，能直接從 NIO `ByteBuffer` 的當前 `position` 讀取到 `limit`，不需要手動搬移 `byte[]`，既簡潔又高效。

### 3. ★ HTTP `POST /api/messages` 與 WebSocket 廣播是否要互通？

- **問題**：
  我們在 Phase 1 寫了 `ChatController` 的 `POST /api/messages`，在 Phase 2 又寫了 `ChatWebSocketHandler`。如果有人透過 HTTP `POST /api/messages` 發了一則訊息，正在連著 WebSocket 的分頁要不要也收到即時廣播？
- **選擇**：
  **要！讓 `ChatController` 也呼叫 `ChatWebSocketHandler.broadcast(saved)`**（或者兩者都透過 `ChatMessageStore` + `ChatWebSocketHandler`）。
- **為什麼**：
  只要在 `ChatController.postMessage()` 存完 `ChatMessageStore` 後順手呼叫一行 `chatWebSocketHandler.broadcast(saved)`，不管你是從 WebSocket 發送、還是用 Phase 1 的 HTTP POST 發送，所有開啟的瀏覽器分頁都會在 `< 100 ms` 內即時跳出新訊息！這讓 Phase 1 與 Phase 2 完美融為一體。

---

## 一、要改哪些檔案、各改什麼

所有檔案皆位於後端 `demo/` 目錄下：

| 檔案 | 性質 | 改什麼 |
|---|---|---|
| `demo/pom.xml` | 改寫 | 新增 `org.springframework.boot:spring-boot-starter-websocket` 依賴 |
| `demo/src/main/java/com/demo/chat/ws/ChatWebSocketHandler.java` | 新增 | 繼承 `BinaryWebSocketHandler`，管理連線 Session 池、解析 `BinaryMessage`、呼叫 `ChatMessageStore.append()` 並廣播二進位幀（見第零節第 1、2 項） |
| `demo/src/main/java/com/demo/chat/config/WebSocketConfig.java` | 新增 | 標註 `@Configuration` 與 `@EnableWebSocket`，實作 `WebSocketConfigurer` 將 `ChatWebSocketHandler` 掛載至 `/ws/chat` |
| `demo/src/main/java/com/demo/chat/controller/ChatController.java` | 改寫 | 注入 `ChatWebSocketHandler`，在 `postMessage` 存入訊息後同步呼叫 `broadcast(saved)`（見第零節第 3 項） |
| `demo/src/test/java/com/demo/chat/controller/ChatControllerTest.java` | 改寫 | 補上 `@MockitoBean private ChatWebSocketHandler chatWebSocketHandler;` 以配合 `ChatController` 新增的建構子參數 |
| `demo/src/test/java/com/demo/chat/ws/ChatWebSocketHandlerTest.java` | 新增 | JUnit 5 測試：驗證多客戶端二進位廣播、斷線移除、非法 Protobuf 二進位處理、拒絕 `TextMessage`（`1003` 關閉連線）與 10 執行緒併發廣播安全 |

---

## 二、對既有功能的衝擊與破壞性風險

- **資料遷移**：沒有。
- **相容性**：不修改 `proto/demo/chat/chat.proto`，Phase 1 的 `GET /api/messages` 與 `POST /api/messages` 契約 100% 相容。
- **既有測試衝擊**：因為 `ChatController` 新增注入了 `ChatWebSocketHandler`，原本只載入 Web 切片的 `@WebMvcTest(ChatController.class)`（即 `ChatControllerTest`）需要多加一行 `@MockitoBean private ChatWebSocketHandler chatWebSocketHandler;`，否則 Spring 測試容器會找不到該 Bean。

### 已知風險

| 風險 | 緩解 |
|---|---|
| 某個客戶端分頁網路異常卡死，廣播時 `session.sendMessage()` 拋出 `IOException` 中斷整個迴圈，導致後面的分頁收不到訊息 | 在 `broadcast` 迴圈內的**每一個 `session`** 獨立包一層 `try-catch (IOException)`，若單一連線發送失敗僅記錄 `log.warn` 並將其移除，絕不影響其他正常客戶端的廣播 |
| 客戶端傳來空的二進位或缺少 `sender` 的 `ChatMessage` | 捕捉 `InvalidProtocolBufferException` 與 `IllegalArgumentException`，記錄 `log.warn` 並忽略該壞封包，不斷開伺服器或其他人的連線 |

---

## 三、替代方案與取捨

### A. 收到壞封包（無法反序列化或 `sender` 空白）時的處理：記錄 `WARN` 並忽略該幀（★ 建議） vs 直接強制踢掉關閉 `WebSocketSession`

- **方案甲（★ 建議：收到無法解析的 `InvalidProtocolBufferException` 或 `IllegalArgumentException` 時記錄 `WARN` 並忽略該幀；僅在收到違反協定的 `TextMessage` 時由父類別 `1003` 關閉連線）**：
  - **優點**：在教學與開發階段，若單一封包欄位未填好（例如忘記填 `sender`），不會導致整個 WebSocket 長連線斷開還得重新整理網頁。
- **方案乙（任何欄位校驗失敗一律 `session.close(CloseStatus.BAD_DATA)`）**：
  - **為什麼排除**：對前端除錯太嚴苛，稍微送錯一筆空字串就斷線。

---

## 四、步驟拆解與驗證方式（含套件教學與完整程式碼藍圖）

| # | 做什麼 | 怎麼確認沒壞 |
|---|---|---|
| 1 | 修改 `demo/pom.xml` 加入 `spring-boot-starter-websocket` | 於 IDEA Reload Maven Project 或執行 `mvn compile` 通過 |
| 2 | 新增 `ChatWebSocketHandler.java`、`WebSocketConfig.java` 並更新 `ChatController.java` | `mvn compile` 零編譯錯誤 |
| 3 | 更新 `ChatControllerTest.java` 並新增 `ChatWebSocketHandlerTest.java` | 執行 `mvn clean test`，**14 個測試全部綠燈通過** |

---

### 📚 必讀教學：`spring-boot-starter-websocket` 與 `BinaryWebSocketHandler` 必記要點

#### 1. 套件定位：`spring-boot-starter-websocket`

這是 Spring Boot 官方提供的 WebSocket 整合套件（底層使用內嵌 Tomcat 的 Jakarta WebSocket 實作）。
在 Spring 中處理原生 WebSocket 有兩個核心基底類別：
- `TextWebSocketHandler`：傳統 JSON 聊天室用的，專門處理字串 `TextMessage`。
- **`BinaryWebSocketHandler`（★ 本專案主角）**：專門處理二進位 `BinaryMessage`（底層包裝 `java.nio.ByteBuffer`）。**它有一個超棒的內建保護機制**：如果前端有人偷傳 JSON 文字幀（`TextMessage`）過來，`BinaryWebSocketHandler` 預設實作會直接拋出例外並以 WebSocket 狀態碼 **`1003 (CloseStatus.NOT_ACCEPTABLE)`** 拒絕並關閉連線！完美契合我們「100% 二進位、0% JSON」的硬約束。

#### 2. 常用 API 與生命週期四部曲（必記！）

| 類別 / 方法 | 觸發時機与用途 | 必記要點 |
|---|---|---|
| `afterConnectionEstablished(WebSocketSession session)` | 當瀏覽器執行 `new WebSocket(...)` **握手連線成功時**觸發 | 在這裡把 `session` 加進 `Set<WebSocketSession>` 連線池 |
| `handleBinaryMessage(WebSocketSession session, BinaryMessage message)` | 當收到客戶端傳來的 **二進位 Frame** 時觸發 | 1. 用 `message.getPayload()` 拿出 `ByteBuffer`<br>2. 用 `ChatMessage.parseFrom(byteBuffer)` 解碼成 Protobuf 物件 |
| `afterConnectionClosed(WebSocketSession session, CloseStatus status)` | 當瀏覽器關閉分頁或斷線時觸發 | **務必**在這裡把 `session` 從連線池移除，否則會造成記憶體洩漏（Memory Leak）！ |
| `new BinaryMessage(byte[] payload)` | 要把 Protobuf 物件發給客戶端時 | 把 `savedMessage.toByteArray()` 包進 `new BinaryMessage(...)`，再呼叫 `session.sendMessage(binaryMsg)` |
| `@EnableWebSocket` + `WebSocketConfigurer` | 註冊 WebSocket 路由的配置介面 | 呼叫 `registry.addHandler(handler, "/ws/chat").setAllowedOrigins("*")` |

---

### 🛠️ 實作藍圖（請一步步親手撰寫）

#### 步驟 1：在 `demo/pom.xml` 新增 WebSocket 依賴

請打開 `demo/pom.xml`，在 `<dependencies>` 區塊（`spring-boot-starter-web` 下方）加入 `spring-boot-starter-websocket`：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <!-- Spring WebSocket 支援（提供 BinaryWebSocketHandler 與 BinaryMessage） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>
```

*(加完後記得點一下 IDEA 右上角的 Maven 小圖示 **Reload All Maven Projects**，讓 IDEA 下載並索引 WebSocket 類別！)*

---

#### 步驟 2-1：新增 `demo/src/main/java/com/demo/chat/ws/ChatWebSocketHandler.java`

請在 `com.demo.chat` 下建立 `ws` package，並新增 `ChatWebSocketHandler.java`：

```java
package com.demo.chat.ws;

import com.demo.chat.proto.ChatMessage;
import com.demo.chat.store.ChatMessageStore;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 處理 /api/ws/chat 的 Protobuf 二進位 WebSocket 連線與全體廣播。
 * 繼承 BinaryWebSocketHandler，若收到純文字 TextMessage 會自動以 1003 NOT_ACCEPTABLE 拒絕。
 * 見 docs/plans/000008 第零節。
 */
@Slf4j
@Component
public class ChatWebSocketHandler extends BinaryWebSocketHandler {

    /** 執行緒安全的連線 Session 集合（支援最多 10+ 個瀏覽器分頁同時在線） */
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ChatMessageStore chatMessageStore;

    public ChatWebSocketHandler(ChatMessageStore chatMessageStore) {
        this.chatMessageStore = chatMessageStore;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket binary connection established: sessionId={}, activeSessions={}",
                session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WebSocket binary connection closed: sessionId={}, status={}, activeSessions={}",
                session.getId(), status, sessions.size());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        try {
            // 1. 直接從 NIO ByteBuffer 反序列化為 Protobuf ChatMessage 物件
            ChatMessage incoming = ChatMessage.parseFrom(message.getPayload());

            // 2. 寫入共用記憶體儲存庫（分配單調遞增 msg-N 與伺服器時間戳）
            ChatMessage saved = chatMessageStore.append(incoming);
            log.debug("Received WS binary message: id={}, sender={}, type={}",
                    saved.getId(), saved.getSender(), saved.getType());

            // 3. 序列化為二進位並廣播給所有連線中的客戶端
            broadcast(saved);
        } catch (InvalidProtocolBufferException e) {
            log.warn("Dropped malformed Protobuf binary frame from sessionId={}: {}",
                    session.getId(), e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Dropped invalid ChatMessage from sessionId={}: {}",
                    session.getId(), e.getMessage());
        }
    }

    /**
     * 將已儲存的 ChatMessage 轉為 BinaryMessage 廣播給所有連線中的 WebSocketSession。
     */
    public void broadcast(ChatMessage savedMessage) {
        BinaryMessage outboundFrame = new BinaryMessage(savedMessage.toByteArray());

        for (WebSocketSession client : sessions) {
            if (!client.isOpen()) {
                sessions.remove(client);
                continue;
            }
            try {
                // 對單一 session 加鎖，防止多執行緒同時對同一個客戶端寫入 Frame 拋出 IllegalStateException
                synchronized (client) {
                    if (client.isOpen()) {
                        client.sendMessage(outboundFrame);
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to send binary message to sessionId={}, removing session",
                        client.getId(), e);
                sessions.remove(client);
            }
        }
    }

    /**
     * 取得目前在線的 WebSocket 連線數（供測試與監控使用）。
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }
}
```

---

#### 步驟 2-2：新增 `demo/src/main/java/com/demo/chat/config/WebSocketConfig.java`

請在 `com.demo.chat.config` 下新增 `WebSocketConfig.java`：

```java
package com.demo.chat.config;

import com.demo.chat.ws.ChatWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 啟用 Spring 原生 WebSocket 並將二進位處理器掛載至 /ws/chat。
 * 搭配 application.yaml 的 context-path: /api，完整端點為 ws://localhost:8080/api/ws/chat。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler) {
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .setAllowedOrigins("*");
    }
}
```

---

#### 步驟 2-3：更新 `demo/src/main/java/com/demo/chat/controller/ChatController.java`

讓透過 HTTP `POST /api/messages` 發送的訊息也同步呼叫 `chatWebSocketHandler.broadcast(saved)`：

```java
package com.demo.chat.controller;

import com.demo.chat.proto.ChatHistoryResponse;
import com.demo.chat.proto.ChatMessage;
import com.demo.chat.store.ChatMessageStore;
import com.demo.chat.ws.ChatWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("messages")
public class ChatController {

    private static final String PROTOBUF_MEDIA_TYPE = "application/x-protobuf";

    private final ChatMessageStore chatMessageStore;
    private final ChatWebSocketHandler chatWebSocketHandler;

    public ChatController(ChatMessageStore chatMessageStore, ChatWebSocketHandler chatWebSocketHandler) {
        this.chatMessageStore = chatMessageStore;
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @PostMapping(consumes = PROTOBUF_MEDIA_TYPE, produces = PROTOBUF_MEDIA_TYPE)
    public ChatMessage postMessage(@RequestBody ChatMessage input) {
        ChatMessage saved = chatMessageStore.append(input);
        log.debug("Saved protobuf message id={}, type={}", saved.getId(), saved.getType());
        chatWebSocketHandler.broadcast(saved);
        return saved;
    }

    @GetMapping(produces = PROTOBUF_MEDIA_TYPE)
    public ChatHistoryResponse getMessages() {
        return ChatHistoryResponse.newBuilder()
                .addAllMessages(chatMessageStore.getRecentMessages()).build();
    }

}
```

---

#### 步驟 3-1：更新 `demo/src/test/java/com/demo/chat/controller/ChatControllerTest.java`

因為 `ChatController` 的建構子多了一個 `ChatWebSocketHandler`，請在 `ChatControllerTest.java` 裡加上一個 `@MockitoBean`（或 `@Autowired` 搭配 `@Import`）：

```java
// 在 ChatControllerTest 類別內的欄位區加上：
@org.springframework.test.context.bean.override.mockito.MockitoBean
private com.demo.chat.ws.ChatWebSocketHandler chatWebSocketHandler;
```
*(或者在 `@Import({ProtobufConfig.class, ChatMessageStore.class, ChatWebSocketHandler.class})` 直接把 `ChatWebSocketHandler.class` 加進 `@Import` 裡也可以，兩種寫法都能讓測試容器順利建立 `ChatController`！)*

---

#### 步驟 3-2：新增 `demo/src/test/java/com/demo/chat/ws/ChatWebSocketHandlerTest.java`

請在 `src/test/java/com/demo/chat/ws/` 下新增 `ChatWebSocketHandlerTest.java`，驗證二進位廣播、斷線清理、壞封包防護、拒絕 `TextMessage` 與 10 執行緒併發廣播：

```java
package com.demo.chat.ws;

import com.demo.chat.proto.ChatMessage;
import com.demo.chat.proto.MessageType;
import com.demo.chat.store.ChatMessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatWebSocketHandlerTest {

    private ChatMessageStore store;
    private ChatWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        store = new ChatMessageStore();
        handler = new ChatWebSocketHandler(store);
    }

    private WebSocketSession createMockSession(String id, boolean isOpen) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(isOpen);
        return session;
    }

    @Test
    @DisplayName("1. 收到單一客戶端 BinaryMessage 後，應寫入 Store 並廣播給所有在線客戶端")
    void shouldSaveAndBroadcastBinaryMessageToAllSessions() throws Exception {
        WebSocketSession clientA = createMockSession("session-A", true);
        WebSocketSession clientB = createMockSession("session-B", true);

        handler.afterConnectionEstablished(clientA);
        handler.afterConnectionEstablished(clientB);
        assertEquals(2, handler.getActiveSessionCount());

        ChatMessage outgoing = ChatMessage.newBuilder()
                .setSender("Leo")
                .setContent("Hello WebSocket Protobuf!")
                .setType(MessageType.CHAT)
                .build();
        BinaryMessage incomingFrame = new BinaryMessage(outgoing.toByteArray());

        // 由 clientA 發送二進位訊息
        handler.handleMessage(clientA, incomingFrame);

        // 驗證 Store 已存入 1 筆
        assertEquals(1, store.getRecentMessages().size());

        // 驗證 clientA 與 clientB 都收到了廣播的 BinaryMessage，且反序列化後欄位正確
        ArgumentCaptor<BinaryMessage> frameCaptor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(clientA, times(1)).sendMessage(frameCaptor.capture());
        verify(clientB, times(1)).sendMessage(any(BinaryMessage.class));

        ChatMessage broadcasted = ChatMessage.parseFrom(frameCaptor.getValue().getPayload());
        assertEquals("msg-1", broadcasted.getId());
        assertEquals("Leo", broadcasted.getSender());
        assertEquals("Hello WebSocket Protobuf!", broadcasted.getContent());
        assertTrue(broadcasted.getTimestamp() > 0);
    }

    @Test
    @DisplayName("2. 客戶端斷線後應從連線池移除，後續廣播不再發送給該 Session")
    void shouldRemoveSessionOnClose() throws Exception {
        WebSocketSession clientA = createMockSession("session-A", true);
        handler.afterConnectionEstablished(clientA);
        assertEquals(1, handler.getActiveSessionCount());

        handler.afterConnectionClosed(clientA, CloseStatus.NORMAL);
        assertEquals(0, handler.getActiveSessionCount());
    }

    @Test
    @DisplayName("3. 收到損毀的二進位封包或空白 sender 時，應安全忽略且不影響連線")
    void shouldIgnoreMalformedBinaryOrBlankSender() throws Exception {
        WebSocketSession clientA = createMockSession("session-A", true);
        handler.afterConnectionEstablished(clientA);

        // 傳送損毀的隨機 byte
        handler.handleMessage(clientA, new BinaryMessage(new byte[]{0x08, (byte) 0xFF, (byte) 0xFF}));
        // 傳送空白 sender
        ChatMessage blankSenderMsg = ChatMessage.newBuilder().setSender("  ").setContent("test").build();
        handler.handleMessage(clientA, new BinaryMessage(blankSenderMsg.toByteArray()));

        assertEquals(0, store.getRecentMessages().size());
        verify(clientA, never()).sendMessage(any());
        assertEquals(1, handler.getActiveSessionCount());
    }

    @Test
    @DisplayName("4. 收到純文字 TextMessage (如 JSON) 時，BinaryWebSocketHandler 應以 1003 NOT_ACCEPTABLE 關閉連線")
    void shouldRejectTextMessageWithNotAcceptable() throws Exception {
        WebSocketSession clientA = createMockSession("session-A", true);
        handler.afterConnectionEstablished(clientA);

        handler.handleMessage(clientA, new TextMessage("{\"sender\":\"Leo\"}"));

        verify(clientA, times(1)).close(CloseStatus.NOT_ACCEPTABLE);
    }

    @Test
    @DisplayName("5. 10 個客戶端同時併發發送 100 則二進位訊息，應全部成功儲存並完成 1,000 次廣播投遞")
    void shouldHandleConcurrentBinaryBroadcastsSafely() throws Exception {
        int clientCount = 10;
        int messagesPerClient = 10;
        WebSocketSession[] clients = new WebSocketSession[clientCount];

        for (int i = 0; i < clientCount; i++) {
            clients[i] = createMockSession("session-" + i, true);
            handler.afterConnectionEstablished(clients[i]);
        }

        ExecutorService pool = Executors.newFixedThreadPool(clientCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(clientCount);

        for (int i = 0; i < clientCount; i++) {
            final int senderIdx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int m = 0; m < messagesPerClient; m++) {
                        ChatMessage msg = ChatMessage.newBuilder()
                                .setSender("User-" + senderIdx)
                                .setContent("Msg-" + m)
                                .setType(MessageType.CHAT)
                                .build();
                        handler.handleBinaryMessage(clients[senderIdx], new BinaryMessage(msg.toByteArray()));
                    }
                } catch (Exception e) {
                    fail("併發廣播不應拋出例外: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        int totalMessages = clientCount * messagesPerClient;
        assertEquals(totalMessages, store.getRecentMessages().size());

        // 每個客戶端都應該收到全部 100 則廣播訊息
        for (WebSocketSession client : clients) {
            verify(client, times(totalMessages)).sendMessage(any(BinaryMessage.class));
        }
    }
}
```

---

### 哪些東西要有測試

| 對象 | 要 | 為什麼 |
|---|---|---|
| `ChatWebSocketHandler` 二進位解析、廣播與 `ChatMessageStore` 連動 | 要（`ChatWebSocketHandlerTest` 測試 1～3） | 確保 `ByteBuffer` ➔ `ChatMessage` ➔ `BinaryMessage` 轉換無誤且壞封包不會弄垮伺服器 |
| 拒絕純文字 `TextMessage`（`1003 NOT_ACCEPTABLE`） | 要（測試 4） | 守護「100% 二進位、0% JSON」硬約束 |
| 10 執行緒併發廣播安全 | 要（測試 5） | 驗證 `ConcurrentHashMap.newKeySet()` 與 `synchronized (client)` 在高併發下無競態錯誤 |

### 預估

約 25～30 分鐘。

---

## 五、不在本任務範圍

- 前端 WebSocket 連線封裝與畫面整合（屬於下一份報告 **`000009`**）。

---

## 六、我自己決定、核准時可以推翻的

| 決定 | 推翻的話怎麼改 |
|---|---|
| `ChatController.postMessage` 存入訊息後也同步呼叫 `chatWebSocketHandler.broadcast(saved)` | 這樣能讓 HTTP POST 與 WebSocket 共享即時廣播；若你希望 HTTP POST 與 WebSocket 完全獨立不互相觸發廣播，核准時告訴我，拿掉那一行即可 |

---

## 七、結果

**完成日期**：2026-10-20

- **步驟 1～3（使用者親手實作與驗證）**：
  - `demo/pom.xml` 新增 `spring-boot-starter-websocket`。
  - 完成 `ChatWebSocketHandler.java`、`WebSocketConfig.java`，並於 `ChatController.java` 串接 `broadCast(saved)`。
  - 完成 `ChatWebSocketHandlerTest.java` 共 5 項測試（其中測試 4 使用 `ArgumentCaptor<CloseStatus>` 擷取並驗證 `1003` 狀態碼，避開 Spring 預設 `reason` 字串差異，相關原理整理於 `docs/notes/07`）。
  - 執行 `mvn clean test`：**14 個測試全部綠燈通過**（`ChatProtoSerializationTest` 3 個 + `ChatMessageStoreTest` 2 個 + `ChatControllerTest` 3 個 + `DemoApplicationTests` 1 個 + `ChatWebSocketHandlerTest` 5 個）。
- **沒驗證到的東西**：
  - 瀏覽器真實 WebSocket 多分頁連線與 `< 100 ms` 即時雙向廣播（將於下一份 `000009` 實機驗證）。
- **與原計畫不同的地方**：無。

