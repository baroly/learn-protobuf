# 000005 - 後端記憶體訊息儲存庫與 Protobuf HTTP REST API（含併發與 MockMvc 測試）

**狀態**：已完成（2026-10-19）
**日期**：2026-10-17
**前置**：`000004` 已核准、Phase 0（`000002`）已完成。

這份報告在後端 `demo/` 實作 **Phase 1 的所有後端元件與自動化測試**：
1. 建立執行緒安全的記憶體訊息儲存庫 `ChatMessageStore`（分配單調遞增 ID、填入伺服器時間戳、保留最近 1,000 筆訊息）。
2. 註冊 Spring 內建的 `ProtobufHttpMessageConverter`，讓 Spring MVC 原生讀寫 `application/x-protobuf` 二進位封包。
3. 實作 `ChatController`（`POST /api/messages` 與 `GET /api/messages`）與 `GlobalExceptionHandler` 統一例外處理。
4. 撰寫 `ChatMessageStoreTest`（驗證 1,000 筆容量淘汰與多執行緒併發序號順序）與 `ChatControllerTest`（使用 `MockMvc` 驗證二進位收發、拒絕 JSON `415` 與拒絕空發送者 `400`）。

做完之後：
- 在 `demo/` 執行 `mvn clean test`，除了原本 Phase 0 的 4 個測試外，新增的 **Store 併發/容量測試**與 **Controller `MockMvc` 二進位整合測試**全部綠燈通過。
- 啟動 `mvn spring-boot:run`，後端在 `http://localhost:8080/api/messages` 就緒，隨時可接受前端 `000006` 的 `application/x-protobuf` 請求。

---

## 零、先定 2 個結構問題

### 1. ★ 多執行緒下「序號分配 (`AtomicLong`)」與「寫入佇列 (`ConcurrentLinkedDeque`)」的順序一致性

- **問題**：如果執行緒 A 先拿到序號 `msg-1`、執行緒 B 後拿到序號 `msg-2`，但執行緒 B 卻先執行完 `deque.addLast(msg-2)`，佇列裡的順序就會變成 `[msg-2, msg-1]`（序號亂序）；而且 `ConcurrentLinkedDeque.size()` 需要遍歷節點，高併發下檢查 `size() > 1000` 再 `pollFirst()` 會有競態條件（Race Condition）。
- **選擇**：在 `ChatMessageStore.append()` 寫入方法上使用 `synchronized`（或鎖定內部寫入鎖）保護「分配序號 → `addLast` → 超過上限 `pollFirst`」這段臨界區（Critical Section）；而讀取 `getRecentMessages()` 則直接遍歷 `ConcurrentLinkedDeque` 回傳不可變列表。
- **為什麼**：記憶體內單次寫入只需幾百奈秒（ns），鎖住寫入臨界區能 100% 保證**「序號順序 ≣ 佇列順序」**且**「容量上限精確不超過 1,000 筆」**，同時讓多執行緒讀取保持無鎖（Lock-Free）高效能。
- **連帶影響**：`ChatMessageStoreTest` 的多執行緒併發測試將能驗證最後留存的 1,000 筆訊息 ID 嚴格遞增為 `msg-201` ～ `msg-1200`，絕不亂序。

### 2. ★ 如何確保 `/api/messages` 100% 只接受與回傳 `application/x-protobuf`（拒絕 JSON）

- **問題**：`spring-boot-starter-web` 預設內建 Jackson (`MappingJackson2HttpMessageConverter`)。如果不在 Controller 明確限制 Media Type，當客戶端傳送 `Content-Type: application/json` 時，Spring 可能會嘗試用 Jackson 去反序列化 Protobuf 的生成的類別而報出奇怪的反射錯誤，而不是標準的 `415 Unsupported Media Type`。
- **選擇**：在 `ChatController` 的 `@PostMapping` 明確宣告 `consumes = "application/x-protobuf", produces = "application/x-protobuf"`，在 `@GetMapping` 明確宣告 `produces = "application/x-protobuf"`。
- **為什麼**：透過 Spring MVC 的路由協商機制，任何非 `application/x-protobuf` 的請求在進入業務邏輯前就會被直接攔截並回傳標準 HTTP `415 Unsupported Media Type`（或 `406 Not Acceptable`），100% 落實架構硬約束。

---

## 一、要改哪些檔案、各改什麼

所有檔案皆位於後端 `demo/` 模組下（基礎 package 為 `com.demo.chat`）：

### 後端配置、儲存層與 Controller（第零節第 1、2 項）

| 檔案 | 性質 | 改什麼 |
|---|---|---|
| `demo/src/main/java/com/demo/chat/config/ProtobufConfig.java` | 新增 | 註冊 `ProtobufHttpMessageConverter` `@Bean`，啟用 Spring MVC 的 Protobuf 二進位訊息轉換 |
| `demo/src/main/java/com/demo/chat/store/ChatMessageStore.java` | 新增 | 封裝 `ConcurrentLinkedDeque<ChatMessage>` 與 `AtomicLong`，實作 1,000 筆上限淘汰、ID 與時間戳分配 |
| `demo/src/main/java/com/demo/chat/controller/ChatController.java` | 新增 | 實作 `POST /api/messages` 與 `GET /api/messages`（強制 `application/x-protobuf`） |
| `demo/src/main/java/com/demo/chat/controller/GlobalExceptionHandler.java` | 新增 | `@RestControllerAdvice` 統一捕捉 `IllegalArgumentException` 與二進位解析失敗（`HttpMessageNotReadableException`），回傳 `400 Bad Request` 並記錄 `WARN` 日誌 |

### 後端單元測試與整合測試

| 檔案 | 性質 | 改什麼 |
|---|---|---|
| `demo/src/test/java/com/demo/chat/store/ChatMessageStoreTest.java` | 新增 | 測試序號分配、超過 1,000 筆自動淘汰最舊訊息、以及 10 執行緒併發寫入 1,200 筆的執行緒安全性與順序一致性 |
| `demo/src/test/java/com/demo/chat/controller/ChatControllerTest.java` | 新增 | 使用 `@WebMvcTest` + `MockMvc` 測試 `POST` / `GET` 的 `application/x-protobuf` 二進位收發、傳送 JSON 回傳 `415`、以及傳送損毀二進位或空發送者回傳 `400` |

---

## 二、對既有功能的衝擊與破壞性風險

- **資料遷移**：沒有。
- **相容性**：不修改 `proto/demo/chat/chat.proto`，與 Phase 0 的 `ChatProtoSerializationTest` 及前端生成的 `chat.js` 100% 相容。
- **既有行為的改變**：沒有（純新增 Web API 與記憶體儲存層）。

### 已知風險

| 風險 | 緩解 |
|---|---|
| 客戶端傳送損毀的 Protobuf `byte[]` 時，Spring 預設可能只印預設錯誤而缺少明確的 WARN 紀錄 | 在 `GlobalExceptionHandler` 攔截 `HttpMessageNotReadableException`，以 `log.warn` 記錄原因並回傳乾淨的 `400 Bad Request` |

---

## 三、替代方案與取捨

### A. Spring MVC 整合方式：使用 Spring 內建 `ProtobufHttpMessageConverter`（★ 建議） vs Controller 手動收發 `byte[]`

- **方案甲（★ 建議：註冊 `ProtobufHttpMessageConverter` `@Bean`，Controller 直接收發 `ChatMessage` 與 `ChatHistoryResponse`）**：
  - **優點**：這是 Spring Boot 官方標準做法。Controller 簽章直接寫 `public ChatMessage sendMessage(@RequestBody ChatMessage request)`，由 Spring 在底層自動呼叫 `ChatMessage.parseFrom(inputStream)` 與 `message.writeTo(outputStream)`，程式碼乾淨且自動處理 Content-Type 與 `Content-Length`。
- **方案乙（Controller 簽章寫 `@RequestBody byte[] rawBytes`，自己在裡面呼叫 `ChatMessage.parseFrom(rawBytes)`）**：
  - **為什麼排除**：每個端點都要重複寫 try-catch `InvalidProtocolBufferException` 與 `toByteArray()`，沒有發揮 Spring 框架 MessageConverter 的架構價值。

---

## 四、步驟拆解與驗證方式（含套件教學與完整程式碼藍圖）

| # | 做什麼 | 怎麼確認沒壞 |
|---|---|---|
| 1 | 建立 `ChatMessageStore.java` 與單元測試 `ChatMessageStoreTest.java` | 在 `demo/` 執行 `mvn test -Dtest=ChatMessageStoreTest` 綠燈通過 |
| 2 | 建立 `ProtobufConfig.java`、`ChatController.java`、`GlobalExceptionHandler.java` 與整合測試 `ChatControllerTest.java` | 在 `demo/` 執行 `mvn clean test` 全部測試（含 Phase 0 與 Phase 1）綠燈通過 |

---

### 📚 必讀教學：本報告使用的 Spring Protobuf 與並行工具詳解

#### 1. `ProtobufHttpMessageConverter`（位於 `org.springframework.http.converter.protobuf`）

- **套件定位**：
  它直接內建在 `spring-web`（包含於 `spring-boot-starter-web`）中，不需要額外裝任何第三方套件！
  平時我們寫 Spring Boot REST API 時，之所以能把 Java 物件自動轉成 JSON，是因為 Spring 預設掛載了 `MappingJackson2HttpMessageConverter`。
  當我們在 `@Configuration` 裡多註冊一個 `new ProtobufHttpMessageConverter()` 的 `@Bean` 時，Spring Boot 的自動配置就會把它加進轉換器清單中。
- **運作原理與常用常數**：
  1. **何時觸發？**
     當 Controller 方法的參數或回傳值型別繼承自 `com.google.protobuf.Message`（我們生成的 `ChatMessage` 與 `ChatHistoryResponse` 都繼承自它），且 HTTP Header 的 `Content-Type` 或 `Accept` 為 **`application/x-protobuf`** 時，Spring 就會交給 `ProtobufHttpMessageConverter` 處理。
  2. **底層幫你做了什麼？**
     - **讀取 Request（反序列化）**：自動從 `HttpInputStream` 讀取二進位串流，反射呼叫 `ChatMessage.newBuilder().mergeFrom(inputStream).build()`。如果二進位損毀，會拋出 `HttpMessageNotReadableException`。
     - **寫出 Response（序列化）**：自動設定 Response Header `Content-Type: application/x-protobuf` 與 `X-Protobuf-Schema: chat.proto`、`X-Protobuf-Message: demo.chat.ChatMessage`，並呼叫 `message.writeTo(outputStream)` 把二進位寫回給瀏覽器！
  3. **必記常數**：
     - `ProtobufHttpMessageConverter.PROTOBUF`：代表 `MediaType("application", "x-protobuf")`，在寫 `MockMvc` 測試時可以直接拿來當 `.contentType(ProtobufHttpMessageConverter.PROTOBUF)` 用！

---

#### 2. `ConcurrentLinkedDeque`、`AtomicLong` 與 `CountDownLatch`（Java 標準庫 `java.util.concurrent`）

- **套件定位**：
  - `AtomicLong`：利用 CPU 底層的 CAS（Compare-And-Swap）指令實現執行緒安全的遞增計數器，適合用來產生單調遞增的訊息序號（`sequence.incrementAndGet()`）。
  - `ConcurrentLinkedDeque`：雙向無鎖並行佇列（Double-Ended Queue），支援從尾端加入新訊息（`addLast`）、從頭端淘汰最舊訊息（`pollFirst`）。
  - `CountDownLatch`（倒數門閂）：在單元測試中用來控制多執行緒「同時起跑」與「等待全部執行緒跑完再驗收」的同步工具。
- **必記要點 1（為什麼我們在 `append` 加了 `synchronized`）**：
  單獨呼叫 `AtomicLong.incrementAndGet()` 是執行緒安全的，單獨呼叫 `ConcurrentLinkedDeque.addLast()` 也是執行緒安全的；**但把兩個執行緒安全的操作組合在一起時，並不會自動變成原子操作（Atomic）**！
  為了保證「拿到 1 號的人一定比拿到 2 號的人先放進佇列」以及「淘汰超過 1,000 筆的檢查不會被插隊」，在寫入方法加上 `synchronized` 是最簡單、最穩固的做法。
- **必記要點 2（`CountDownLatch` 的 3 個核心 API 與「雙門閂」併發測試模式）**：
  - `new CountDownLatch(N)`：設定需要倒數 `N` 次才會打開的柵欄。
  - `latch.await()`：站在柵欄前等待，直到計數器歸 `0` 才放行（可帶超時時間 `await(10, TimeUnit.SECONDS)`）。
  - `latch.countDown()`：將計數器減 `1`（減到 `0` 的瞬間喚醒所有等待中的執行緒）。**務必放在 `finally` 區塊中**，避免執行緒拋錯導致主執行緒永遠卡住。
  - 在 `ChatMessageStoreTest` 中我們使用了兩個門閂：
    1. **`startLatch = new CountDownLatch(1)`（起跑槍）**：讓 10 個執行緒建立好後先全部卡在 `startLatch.await()` 待命，等主執行緒一聲令下 `startLatch.countDown()`，10 個執行緒才會在**同一微秒同時衝出去**搶鎖，真正測到高併發碰撞。
    2. **`doneLatch = new CountDownLatch(10)`（終點線裁判）**：主執行緒呼叫 `doneLatch.await(...)` 等待，每個工作執行緒跑完 120 筆後呼叫 `doneLatch.countDown()`，集滿 10 人歸零後主執行緒才開始驗收 `assertThat(...)`。

---

#### 3. `MockMvc` 二進位測試技巧（位於 `org.springframework.test.web.servlet`）

- 平常測 JSON API 時，我們習慣用 `.content("{\"name\":\"Leo\"}")`（傳字串）跟 `.andExpect(jsonPath("$.name").value("Leo"))`。
- **測 Protobuf 二進位 API 時怎麼寫？**
  1. **傳送二進位 Request Body**：直接傳入 `byte[]`！也就是 `.content(requestMessage.toByteArray())`。
  2. **取出二進位 Response Body 並驗證**：
     先接住回應的 `byte[]`：
     ```java
     byte[] responseBytes = mockMvc.perform(...)
             .andExpect(status().isOk())
             .Return().getResponse().getContentAsByteArray();
     ```
     再用 `ChatMessage.parseFrom(responseBytes)` 還原成物件，接著就能用 AssertJ `assertThat(parsed.getId()).isEqualTo(...)` 舒服地驗證每一個欄位了！

---

### 🛠️ 親手實作藍圖（共 2 步）

#### 步驟 1：建立記憶體訊息儲存庫 `ChatMessageStore` 與單元測試

##### 1-1. 新增 `demo/src/main/java/com/demo/chat/store/ChatMessageStore.java`

請在 `com.demo.chat` 下建立 `store` package，並新增 `ChatMessageStore.java`：

```java
package com.demo.chat.store;

import com.demo.chat.proto.ChatMessage;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 負責在記憶體中儲存最近 1,000 則聊天訊息，並統一分配單調遞增 ID 與伺服器時間戳。
 * 設計理由見 docs/architecture.md 決策 5 與 docs/plans/000005 第零節。
 */
@Repository
public class ChatMessageStore {

    /** 記憶體保留的最大訊息筆數上限（硬約束：1,000 則） */
    public static final int MAX_MESSAGES = 1000;

    private final ConcurrentLinkedDeque<ChatMessage> messages = new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong(0);

    /**
     * 驗證並寫入一筆新訊息，由伺服器補齊單調遞增 ID 與當前時間戳。
     * 使用 synchronized 確保多執行緒下「序號分配」與「入列順序、容量淘汰」完全一致。
     */
    public synchronized ChatMessage append(ChatMessage incoming) {
        if (incoming == null || incoming.getSender().isBlank()) {
            throw new IllegalArgumentException("發送者名稱 (sender) 不可為空");
        }

        long seq = sequence.incrementAndGet();
        ChatMessage saved = incoming.toBuilder()
                .setId("msg-" + seq)
                .setTimestamp(System.currentTimeMillis())
                .build();

        messages.addLast(saved);

        while (messages.size() > MAX_MESSAGES) {
            messages.pollFirst();
        }

        return saved;
    }

    /**
     * 取得目前記憶體中保留的所有歷史訊息（由舊到新排序的不可變快照）。
     */
    public List<ChatMessage> getRecentMessages() {
        return List.copyOf(new ArrayList<>(messages));
    }

    /**
     * 清空所有訊息並重置序號（僅供測試隔離使用）。
     */
    public synchronized void clear() {
        messages.clear();
        sequence.set(0);
    }
}
```

##### 1-2. 新增單元測試 `demo/src/test/java/com/demo/chat/store/ChatMessageStoreTest.java`

在 `src/test/java/com/demo/chat/store` 下建立 `ChatMessageStoreTest.java`，驗證容量淘汰與 10 執行緒併發寫入：

```java
package com.demo.chat.store;

import com.demo.chat.proto.ChatMessage;
import com.demo.chat.proto.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 驗證 ChatMessageStore 的序號分配、1,000 筆容量上限淘汰與多執行緒併發安全性。
 * 見 docs/plans/000005 第四節。
 */
class ChatMessageStoreTest {

    private ChatMessageStore store;

    @BeforeEach
    void setUp() {
        store = new ChatMessageStore();
    }

    @Test
    @DisplayName("1. 寫入訊息時應由伺服器自動分配 msg-N 序號與時間戳，且空 sender 應拋出 IllegalArgumentException")
    void shouldAssignIdAndTimestampAndRejectBlankSender() {
        ChatMessage incoming = ChatMessage.newBuilder()
                .setSender("Leo")
                .setContent("Hello")
                .setType(MessageType.CHAT)
                .build();

        ChatMessage saved = store.append(incoming);

        assertThat(saved.getId()).isEqualTo("msg-1");
        assertThat(saved.getTimestamp()).isPositive();
        assertThat(saved.getSender()).isEqualTo("Leo");
        assertThat(saved.getContent()).isEqualTo("Hello");

        ChatMessage blankSenderMsg = ChatMessage.newBuilder()
                .setSender("   ")
                .setContent("Invalid")
                .build();

        assertThatThrownBy(() -> store.append(blankSenderMsg))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("2. 寫入超過 1,000 筆上限時，應自動淘汰最舊訊息，且多執行緒併發寫入下序號與順序嚴格一致")
    void shouldEvictOldestMessagesUnderConcurrentWrites() throws InterruptedException {
        int threadCount = 10;
        int messagesPerThread = 120; // 總共寫入 1,200 筆（超過 1,000 筆上限）
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < messagesPerThread; i++) {
                        store.append(ChatMessage.newBuilder()
                                .setSender("User-" + threadId)
                                .setContent("Message " + i)
                                .setType(MessageType.CHAT)
                                .build());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 10 個執行緒同時起跑寫入
        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();

        List<ChatMessage> recent = store.getRecentMessages();
        // 驗證容量精確保持在 1,000 筆上限
        assertThat(recent).hasSize(ChatMessageStore.MAX_MESSAGES);

        // 驗證前 200 筆 (msg-1 ~ msg-200) 已被淘汰，留存的 1,000 筆嚴格照 msg-201 ~ msg-1200 排序
        for (int i = 0; i < recent.size(); i++) {
            long expectedSeq = 201L + i;
            assertThat(recent.get(i).getId()).isEqualTo("msg-" + expectedSeq);
        }
    }
}
```

---

#### 步驟 2：建立 `ProtobufConfig`、`ChatController`、`GlobalExceptionHandler` 與 `MockMvc` 測試

##### 2-1. 新增 `demo/src/main/java/com/demo/chat/config/ProtobufConfig.java`

在 `com.demo.chat.config` 下建立 `ProtobufConfig.java`：

```java
package com.demo.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter;

/**
 * 註冊 ProtobufHttpMessageConverter，讓 Spring MVC 支援 application/x-protobuf 二進位序列化與反序列化。
 * 見 docs/architecture.md 決策 2 與 docs/plans/000005 第四節。
 */
@Configuration
public class ProtobufConfig {

    @Bean
    public ProtobufHttpMessageConverter protobufHttpMessageConverter() {
        return new ProtobufHttpMessageConverter();
    }
}
```

##### 2-2. 新增 `demo/src/main/java/com/demo/chat/controller/ChatController.java`

在 `com.demo.chat.controller` 下建立 `ChatController.java`：

```java
package com.demo.chat.controller;

import com.demo.chat.proto.ChatHistoryResponse;
import com.demo.chat.proto.ChatMessage;
import com.demo.chat.store.ChatMessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供 100% 使用 Protobuf 二進位格式 (application/x-protobuf) 的聊天訊息 REST API。
 * 見 docs/plans/000005 第零節與第一節。
 */
@RestController
@RequestMapping("/api/messages")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final String PROTOBUF_MEDIA_TYPE = "application/x-protobuf";

    private final ChatMessageStore messageStore;

    public ChatController(ChatMessageStore messageStore) {
        this.messageStore = messageStore;
    }

    /**
     * 接收前端傳來的單筆 ChatMessage 二進位封包，存入記憶體並回傳補齊伺服器 ID 與時間戳後的 ChatMessage 二進位封包。
     */
    @PostMapping(consumes = PROTOBUF_MEDIA_TYPE, produces = PROTOBUF_MEDIA_TYPE)
    public ChatMessage sendMessage(@RequestBody ChatMessage request) {
        ChatMessage saved = messageStore.append(request);
        log.debug("Saved protobuf message id={}, type={}", saved.getId(), saved.getType());
        return saved;
    }

    /**
     * 回傳目前記憶體中保留的歷史訊息列表，封裝為 ChatHistoryResponse 二進位封包。
     */
    @GetMapping(produces = PROTOBUF_MEDIA_TYPE)
    public ChatHistoryResponse getMessages() {
        return ChatHistoryResponse.newBuilder()
                .addAllMessages(messageStore.getRecentMessages())
                .build();
    }
}
```

##### 2-3. 新增 `demo/src/main/java/com/demo/chat/controller/GlobalExceptionHandler.java`

在 `com.demo.chat.controller` 下建立 `GlobalExceptionHandler.java`：

```java
package com.demo.chat.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 統一將業務參數錯誤與損毀的 Protobuf 二進位封包轉譯為 HTTP 400 Bad Request，不外洩內部 stack trace。
 * 見 .claude/skills/backend-style/SKILL.md 錯誤處理規範。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid request argument: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        log.warn("Failed to parse binary protobuf payload: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
}
```

##### 2-4. 新增整合測試 `demo/src/test/java/com/demo/chat/controller/ChatControllerTest.java`

在 `src/test/java/com/demo/chat/controller` 下建立 `ChatControllerTest.java`：

```java
package com.demo.chat.controller;

import com.demo.chat.config.ProtobufConfig;
import com.demo.chat.proto.ChatHistoryResponse;
import com.demo.chat.proto.ChatMessage;
import com.demo.chat.proto.MessageType;
import com.demo.chat.store.ChatMessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 驗證 ChatController 的 application/x-protobuf 二進位收發契約與 Content-Type 協商。
 * 見 docs/plans/000005 第四節。
 */
@WebMvcTest(ChatController.class)
@Import({ProtobufConfig.class, ChatMessageStore.class, GlobalExceptionHandler.class})
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatMessageStore messageStore;

    @BeforeEach
    void setUp() {
        messageStore.clear();
    }

    @Test
    @DisplayName("1. POST /api/messages 傳送 Protobuf 二進位應成功儲存並回傳補齊 ID 的二進位，隨後 GET /api/messages 可取得列表")
    void shouldSendAndGetMessagesViaProtobufBinary() throws Exception {
        ChatMessage requestMsg = ChatMessage.newBuilder()
                .setSender("Leo")
                .setContent("Hello Spring Protobuf REST!")
                .setType(MessageType.CHAT)
                .build();

        // 1. 發送 POST /api/messages (Content-Type: application/x-protobuf)
        byte[] postResponseBytes = mockMvc.perform(post("/api/messages")
                        .contentType(ProtobufHttpMessageConverter.PROTOBUF)
                        .accept(ProtobufHttpMessageConverter.PROTOBUF)
                        .content(requestMsg.toByteArray()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(ProtobufHttpMessageConverter.PROTOBUF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        ChatMessage savedMsg = ChatMessage.parseFrom(postResponseBytes);
        assertThat(savedMsg.getId()).isEqualTo("msg-1");
        assertThat(savedMsg.getSender()).isEqualTo("Leo");
        assertThat(savedMsg.getContent()).isEqualTo("Hello Spring Protobuf REST!");
        assertThat(savedMsg.getTimestamp()).isPositive();

        // 2. 發送 GET /api/messages 取得 ChatHistoryResponse 二進位
        byte[] getResponseBytes = mockMvc.perform(get("/api/messages")
                        .accept(ProtobufHttpMessageConverter.PROTOBUF))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(ProtobufHttpMessageConverter.PROTOBUF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        ChatHistoryResponse history = ChatHistoryResponse.parseFrom(getResponseBytes);
        assertThat(history.getMessagesCount()).isEqualTo(1);
        assertThat(history.getMessages(0)).isEqualTo(savedMsg);
    }

    @Test
    @DisplayName("2. 當客戶端使用 application/json 發送 POST 時，應回傳 415 Unsupported Media Type")
    void shouldRejectJsonContentTypeWith415() throws Exception {
        String jsonPayload = "{\"sender\":\"Leo\",\"content\":\"JSON is not allowed\"}";

        mockMvc.perform(post("/api/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("3. 當傳送損毀的 Protobuf 二進位或空 sender 時，應回傳 400 Bad Request")
    void shouldReturn400ForCorruptedBinaryOrBlankSender() throws Exception {
        byte[] corruptedBytes = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        mockMvc.perform(post("/api/messages")
                        .contentType(ProtobufHttpMessageConverter.PROTOBUF)
                        .accept(ProtobufHttpMessageConverter.PROTOBUF)
                        .content(corruptedBytes))
                .andExpect(status().isBadRequest());

        ChatMessage blankSenderMsg = ChatMessage.newBuilder()
                .setSender("  ")
                .setContent("Missing sender")
                .build();

        mockMvc.perform(post("/api/messages")
                        .contentType(ProtobufHttpMessageConverter.PROTOBUF)
                        .accept(ProtobufHttpMessageConverter.PROTOBUF)
                        .content(blankSenderMsg.toByteArray()))
                .andExpect(status().isBadRequest());
    }
}
```

---

### 哪些東西要有測試

| 對象 | 要 | 為什麼 |
|---|---|---|
| `ChatMessageStore` 1,000 筆容量淘汰與 10 執行緒併發寫入順序（`ChatMessageStoreTest`） | **一定要**（`CLAUDE.md`） | 併發與容量上限在手動測試極難重現，必須靠 `CountDownLatch` 多執行緒測試鎖定 |
| `ChatController` HTTP `application/x-protobuf` 協商與 `415`/`400` 邊界（`ChatControllerTest`） | **一定要**（`CLAUDE.md`） | 確保 `ProtobufHttpMessageConverter` 正確掛載且嚴格拒絕 JSON |

### 預估

約 30 分鐘（步驟 1 約 15 分鐘、步驟 2 約 15 分鐘）。

---

## 五、不在本任務範圍

- 前端 `vite.config.ts` 代理設定與 `fetch` 聊天室畫面（將於下一份報告 `000006` 實作）。
- WebSocket 端點與廣播（屬於 **Phase 2**）。

---

## 六、我自己決定、核准時可以推翻的

| 決定 | 推翻的話怎麼改 |
|---|---|
| `GlobalExceptionHandler` 對 `400 Bad Request` 回傳空 Body (`ResponseEntity<Void>`) | 因為專案規定業務端點 0% JSON，錯誤回應不回傳 JSON 字串而直接以 HTTP 狀態碼 (`400` / `415`) 表達最純粹；若未來想定義 `ErrorResponse` proto 訊息可在 Phase 3 擴充 |

---

## 七、結果（完成後填寫）

- **完成日期**：2026-10-19
- **驗證結果**：
  - 於 `demo/` 執行 `mvn clean test`，共 **9 個測試全部綠燈通過**（`ChatMessageStoreTest` 2 個、`ChatControllerTest` 3 個、`ChatProtoSerializationTest` 3 個、`DemoApplicationTests` 1 個）。
  - 驗證了 10 執行緒高併發寫入 1,200 筆訊息時的 1,000 筆容量淘汰與序號嚴格遞增（`msg-201` ～ `msg-1200`）。
  - 驗證了 `ProtobufHttpMessageConverter` 的 `application/x-protobuf` 收發契約、拒絕 `application/json`（回傳 `415 Unsupported Media Type`）與損毀二進位處理（回傳 `400 Bad Request`）。
- **實作偏離與補充筆記**：
  - 於 `application.yaml` 統一配置 `server.servlet.context-path: /api`，`ChatController` 使用 `@RequestMapping("messages")`（`MockMvc` 測試路徑對應為 `"/messages"`），並使用 Lombok `@Slf4j`（已記於 `docs/decisions.md`）。
  - 補充技術筆記 `docs/notes/04`（`CountDownLatch` 雙門閂併發測試）與 `docs/notes/05`（Spring `MockMvc` 流式 API 三部曲與 Protobuf 二進位測試詳解）。

