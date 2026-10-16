# Protobuf 二進位 Wire Format (TLV 與 Varint) 與 Schema 角色

## 1. Tag Byte 的位元結構（1 + 4 + 3）

每個欄位的開頭第一個 byte 是 **Tag（標籤）**，8 個 bits 切為三段：

```text
  [ b7 ]   [ b6  b5  b4  b3 ]   [ b2  b1  b0 ]
   1 bit         4 bits             3 bits
  ──────   ──────────────────   ──────────────
  續讀位        欄位編號            型別代號
  (MSB)      (Field Number)       (Wire Type)
```

- **`b7`（最高位元 MSB）**：Varint 續讀位。`0` 代表這個整數在此 byte 結束；`1` 代表還沒結束、需繼續讀下一個 byte。
- **`b6~b3`（4 bits）**：欄位編號（`= 1, 2, ...`）。4 bits 最大表示 `15`，因此**編號 `1 ~ 15` 的 Tag 永遠只佔 1 byte**。
- **`b2~b0`（3 bits）**：Wire Type（型別代號，僅 `0 ~ 5` 合法，`6` 與 `7` 非法）。

---

## 2. 兩種最常見的 Wire Type 結構

### ① Wire Type `2`（Length-delimited：`string`、`bytes`、巢狀 `message`）
結構為 **`[Tag] + [Length] + [Value]`（TLV）**：
- 第 1 個 byte：Tag（欄位編號 + 型別 `2`）
- 第 2 個 byte（Varint）：宣告後面資料的 byte 長度
- 後續 bytes：原始 UTF-8 字串或子訊息 bytes，直接依長度切出，不需跳脫字元處理。

### ② Wire Type `0`（Varint：`int32`、`int64`、`bool`、`enum`）
結構為 **`[Tag] + [Value]`（無 Length byte）**：
- 第 1 個 byte：Tag（欄位編號 + 型別 `0`）
- 第 2~N 個 bytes：數值本身的 Varint 編碼（靠每個 byte 的最高位元 `0/1` 判斷何時結束，因此不需要 Length byte）。
- 若欄位值為 `proto3` 預設值（`0`、`false`、枚舉 `0`），整個欄位（連同 Tag）都不會寫入二進位流中（佔 **0 byte**）。

---

## 3. 為什麼二進位裡的 `[40, 1]` 分得出是 `enum` 還是 `int32`？

在二進位封包中，`enum`、`int32`、`int64`、`bool` 都是 Wire Type `0`，網路上傳輸的 `[40, 1]` 只表達 **「第 5 號欄位的值是 1」**，二進位本身不攜帶它是枚舉還是整數的資訊。

**語意還原全靠前後端各自從 `.proto` 編譯出來的程式碼**：
- 後端 Java 的 `ChatMessage.parseFrom()` 查表得知第 5 號欄位在 `chat.proto` 是 `MessageType`，便將 `1` 轉為 `MessageType.JOIN`。
- 前端 TS/JS 的 `ChatMessage.decode()` 同樣根據 `chat.js` 將第 5 號欄位對應至 `MessageType.JOIN`。
- 這也是為什麼**欄位編號（`= 1, 2...`）一旦定案絕不可重排或隨意改為不相容型別**。
