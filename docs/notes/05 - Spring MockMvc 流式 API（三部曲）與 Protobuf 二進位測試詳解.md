# 05 - Spring MockMvc 流式 API（三部曲）與 Protobuf 二進位測試詳解

在 `ChatControllerTest` 中，這段 `mockMvc.perform(...)` 串接看起來很長，主要是因為 Spring Test 採用了 **Fluent API（流式介面 / 建造者模式）**，並且大量使用了 Java 的 **`import static`（靜態匯入）**。

只要把這串長鏈拆解成 **「三部曲：組裝請求 ➔ 斷言檢查 ➔ 拆封取貨」**，每一層的結構就會非常清晰！

---

## 1. 整體結構拆解圖（三部曲）

以 `ChatControllerTest.java` 第 50～58 行為例：

```java
byte[] postResponseByte = mockMvc
    // ================= 【第一部曲：perform（組裝並發送 HTTP 請求）】 =================
    .perform(
        post("/messages")                                      // 1. 動作與路徑：建立 POST /messages 請求建造者
            .contentType(ProtobufHttpMessageConverter.PROTOBUF)// 2. Request Header (Content-Type)：告訴後端「我寄過去的包裹是 Protobuf」
            .accept(ProtobufHttpMessageConverter.PROTOBUF)     // 3. Request Header (Accept)：告訴後端「我只收 Protobuf 格式的回信」
            .content(requestMsg.toByteArray())                 // 4. Request Body：塞入序列化後的 byte[] 二進位陣列
    )
    // ================= 【第二部曲：andExpect（驗收 HTTP 回應狀態與標頭）】 =================
    .andExpect(status().isOk())                                // 5. 檢查 HTTP 狀態碼是否為 200 OK
    .andExpect(content().contentTypeCompatibleWith(            // 6. 檢查 Response Header 的 Content-Type 是否相容於 application/x-protobuf
        ProtobufHttpMessageConverter.PROTOBUF))
    // ================= 【第三部曲：andReturn（拆開包裹拿出二進位內容）】 =================
    .andReturn()                                               // 7. 結束檢查，拿到完整的測試結果物件 (MvcResult)
    .getResponse()                                             // 8. 從結果中拿出模擬的 HTTP 回應物件 (MockHttpServletResponse)
    .getContentAsByteArray();                                  // 9. 從回應中抽出原始的 byte[] 二進位陣列
```

---

## 2. 為什麼括號裡面還有方法？（認識 `import static` 工廠方法）

初看 `mockMvc.perform(post("/messages"))` 或 `.andExpect(status().isOk())` 最容易困惑的是：**為什麼 `post()`、`status()`、`content()` 不用寫類別名稱就能直接呼叫？**

答案在檔案最上方（第 20～23 行）的 **`import static`**：

| 你寫的簡寫 | 它的真實身分（完整類別與靜態方法） | 它的回傳值（為什麼後面還能繼續 `.xxx()`） |
|---|---|---|
| `post("/messages")` | `MockMvcRequestBuilders.post("/messages")` | 回傳 `MockHttpServletRequestBuilder`（**請求建造者**），所以後面可以繼續接 `.contentType(...)`、`.accept(...)`、`.content(...)` |
| `get("/messages")` | `MockMvcRequestBuilders.get("/messages")` | 同上，建立一個 `GET` 請求建造者 |
| `status()` | `MockMvcResultMatchers.status()` | 回傳 `StatusResultMatchers`（**狀態碼比對器工廠**），後面點出 `.isOk()` (200)、`.isBadRequest()` (400)、`.isUnsupportedMediaType()` (415) |
| `content()` | `MockMvcResultMatchers.content()` | 回傳 `ContentResultMatchers`（**回應內容比對器工廠**），用來檢查回傳的 `Content-Type` 或 Body 內容 |

---

## 3. 第一部曲：`perform(...)` 裡面的參數詳解

`mockMvc.perform(...)` 接收一個 **RequestBuilder（請求建造者）**，你可以把它想像成**「在 Postman 填寫一個 HTTP Request 的各個欄位」**：

1. **`post("/messages")`**：
   - 對應 Postman 的 **Method = `POST`**、**URL = `/messages`**。
   - **注意**：因為 `@WebMvcTest` 只啟動假（Mock）的 DispatcherServlet，不會讀取 `application.yaml` 裡的 `server.servlet.context-path: /api`，所以路徑直接寫 Controller 上的 `@RequestMapping("messages")` 即可。
2. **`.contentType(ProtobufHttpMessageConverter.PROTOBUF)`**：
   - 對應 HTTP Request Header 的 **`Content-Type: application/x-protobuf`**。
   - **意義**：告訴後端伺服器「我現在傳給你的 Request Body 是 Protobuf 二進位格式」。這會對應到 `ChatController` 上的 `@PostMapping(consumes = "application/x-protobuf")`。如果傳成 `MediaType.APPLICATION_JSON`，Spring 就會直接攔截並退回 `415 Unsupported Media Type`。
   - **為什麼用 `ProtobufHttpMessageConverter.PROTOBUF`？**：這是 Spring 內建定義好的常數，等同於 `new MediaType("application", "x-protobuf")`，用常數不會打錯字。
3. **`.accept(ProtobufHttpMessageConverter.PROTOBUF)`**：
   - 對應 HTTP Request Header 的 **`Accept: application/x-protobuf`**。
   - **意義**：告訴後端伺服器「請用 Protobuf 二進位格式回傳結果給我」。這對應到 `ChatController` 上的 `produces = "application/x-protobuf"`。
   - **為什麼 `GET` 請求不用寫 `.contentType(...)`，只需要寫 `.accept(...)`？**：因為 `GET` 請求沒有 Request Body（沒寄東西過去），只需要告訴伺服器「我想收到什麼格式（Accept）」。
4. **`.content(requestMsg.toByteArray())`**：
   - 對應 HTTP **Request Body（請求主體）**。
   - `.content(...)` 有兩個常用多載（Overload）：
     - `content(String)`：測 JSON 時傳字串（如第 88 行的 `.content(jsonPayload)`）。
     - `content(byte[])`：測 Protobuf 二進位時，直接把 `requestMsg.toByteArray()` 產生的 `byte[]` 塞進去！

---

## 4. 第二部曲：`andExpect(...)` 裡面的參數詳解

當 `perform(...)` 執行完畢後，它會回傳一個 `ResultActions` 物件，讓你可以用一連串的 `.andExpect(...)` 來檢查回應是否符合預期：

1. **`.andExpect(status().isOk())`**：
   - 檢查 HTTP Status Code 是否為 `200 OK`。
   - 同理，第 89 行的 `status().isUnsupportedMediaType()` 是檢查 `415`；第 101 行的 `status().isBadRequest()` 是檢查 `400`。
2. **`.andExpect(content().contentTypeCompatibleWith(ProtobufHttpMessageConverter.PROTOBUF))`**：
   - **為什麼用 `contentTypeCompatibleWith(...)` 而不是 `contentType(...)`？**
   - 因為 Spring 的 `ProtobufHttpMessageConverter` 在寫入回應時，有時候會在 Header 附加上字元集或參數（例如 `application/x-protobuf;charset=UTF-8`）。
   - 如果用嚴格相等的 `.contentType(...)`，可能會因為後面多了 `;charset=UTF-8` 而誤判失敗；使用 **`contentTypeCompatibleWith`（相容比對）** 只要主類型與子類型是 `application/x-protobuf` 就會通過，是實務上最穩健的寫法！

---

## 5. 第三部曲：`andReturn().getResponse().getContentAsByteArray()` 詳解

平常測 JSON API 時，我們可以在 `andExpect(jsonPath("$.id").value("msg-1"))` 直接檢查欄位；但 **Protobuf 回傳的是壓縮過的二進位 `byte[]`**，`MockMvc` 看不懂 Protobuf 裡面的欄位，所以我們必須把 `byte[]` 拿出來自己用 `ChatMessage.parseFrom(...)` 解碼：

| 呼叫鏈步驟 | 回傳型別 | 白話解釋 |
|---|---|---|
| `.andReturn()` | `MvcResult` | 「檢查都通過了，請把這次請求與回應的完整紀錄打包給我」 |
| `.getResponse()` | `MockHttpServletResponse` | 「從紀錄中拿出**伺服器回傳的 Response 物件**（裡面有 Status、Headers、Body）」 |
| `.getContentAsByteArray()` | `byte[]` | 「把 Response Body 以**原始二進位陣列 (`byte[]`)** 的形式抽出來」（如果是文字 API 則是叫 `.getContentAsString()`） |

拿到 `byte[] postResponseByte` 之後，就能交給我們在 Phase 0 學過的：
```java
ChatMessage savedMsg = ChatMessage.parseFrom(postResponseByte);
```
把它還原成強型別的 Java 物件，再用 `assertThat(...)` 驗證每個欄位！
