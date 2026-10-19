# 06 - TypeScript 6.0 `Uint8Array` 泛型限制、Zero-Copy 斷言與 `protobufjs` 8KB 記憶體池陷阱

在前端使用 `protobufjs` 搭配瀏覽器 `fetch`（或 WebSocket）傳送二進位封包時，當我們把 `ChatMessage.encode(msg).finish()` 產出的 `requestBytes` 傳給 `fetch(..., { body: ... })`，會遇到兩個非常重要且與效能/正確性息息相關的底層觀念：

1. **為什麼 TypeScript 5.7+ / 6.0 不允許直接寫 `body: requestBytes`？正式環境如何做到 Zero-Copy（零複製）？**
2. **為什麼千萬不能寫 `body: requestBytes.buffer`？（認識 `protobufjs` 的 8KB 共用記憶體池）**

---

## 1. 為什麼 TypeScript 6.0 攔住 `body: requestBytes`？

在 **純 JavaScript 執行期**，`ChatMessage.encode(msg).finish()` 回傳的本身就已經是一個標準的 `Uint8Array`，瀏覽器的 `fetch` 100% 可以直接接收。

但在 **TypeScript 5.7+ / 6.0** 中，微軟將 `Uint8Array` 改為泛型 **`Uint8Array<TArrayBuffer>`**，用來區分底層記憶體是哪一種：

| 底層記憶體類型 | 說明 | 瀏覽器 `fetch` (`BodyInit`) 是否接受？ |
|---|---|---|
| **`ArrayBuffer`** | 一般單執行緒記憶體區塊 | ✅ **接受** (`Uint8Array<ArrayBuffer>`) |
| **`SharedArrayBuffer`** | 多執行緒（Web Worker）共享記憶體（有傳送途中被其他執行緒竄改的風險） | ❌ **拒絕**（DOM 標準禁止直接傳入 `fetch`） |

### 衝突點
- `protobufjs` 自動生成的 `chat.d.ts` 寫的是舊式無泛型的 `finish(): Uint8Array`。
- TypeScript 6.0 看到沒寫泛型的 `Uint8Array`，會自動推論為 **`Uint8Array<ArrayBufferLike>`**（代表底層可能是 `ArrayBuffer`，也可能是 `SharedArrayBuffer`）。
- 因此直接寫 `body: requestBytes` 時，`vue-tsc -b` 就會報錯：
  > `Type 'Uint8Array<ArrayBufferLike>' is not assignable to type 'BodyInit'`

---

## 2. 解決方案與正式環境的 Zero-Copy（零複製）取捨

### ❌ 絕對不要做的事：手改 `src/proto/chat.js` 或 `chat.d.ts`
`src/proto/` 下的檔案是每次執行 `npm run proto` 時由 `pbjs` / `pbts` 自動覆蓋生成的（且不進版控）。任何手動修改在下次重跑 codegen 時都會被洗掉。

### ✅ 兩種合法寫法比較

| 寫法 | 程式碼 | 記憶體行為與適用情境 |
|---|---|---|
| **寫法 A：型別斷言（★ 正式開發推薦：Zero-Copy）** | `body: requestBytes as Uint8Array<ArrayBuffer>` | **零記憶體複製（Zero-Copy）**。`as` 在編譯成 JS 後完全消失，執行期直接傳遞原本的 `Uint8Array` 參考，無任何效能損耗。適合封裝在 `chatApi.ts` 內部。 |
| **寫法 B：重新建構 `Uint8Array`** | `body: new Uint8Array(requestBytes)` | 呼叫建構子建立一個全新的 `Uint8Array<ArrayBuffer>` 並複製一份 bytes。好處是不需寫 `as`，但在大封包高頻率傳輸時會多一次記憶體配置與複製。 |

---

## 3. ⚠️ 致命陷阱：為什麼絕對不能寫 `body: requestBytes.buffer`？

有些人為了繞過 TypeScript 對 `Uint8Array` 的型別檢查，會想說：「既然 `Uint8Array` 有個 `.buffer` 屬性可以拿到底層的 `ArrayBuffer`，那我傳 `body: requestBytes.buffer` 總可以了吧？」

**千萬不可以！這樣寫會把整塊 8KB 的垃圾記憶體一起傳給後端！**

### 原因：`protobufjs` 底層的 8KB Buffer Pool（共用記憶體池）

為了壓榨出極致效能、避免每次呼叫 `encode()` 都向作業系統申請新記憶體（造成 GC 垃圾回收壓力），`protobufjs` 的 `Writer` 在底層預先配置了一塊 **8,192 bytes (8 KB) 的大 `ArrayBuffer` 記憶體池**：

```text
【protobufjs 底層的 8192 bytes 共用 ArrayBuffer (.buffer)】
┌────────────────────┬───────────────────────┬─────────────────────────────────┐
│ 上一筆殘留的舊資料 │ 本次 encode 的 34 bytes│ 尚未使用的空白或歷史殘留記憶體  │
│ (offset: 0 ~ 99)   │ (offset: 100 ~ 133)   │ (offset: 134 ~ 8191)            │
└────────────────────┴───────────────────────┴─────────────────────────────────┘
                      ▲                     ▲
                      └─── requestBytes ────┘
                  (Uint8Array 檢視窗：byteOffset=100, byteLength=34)
```

1. **當你傳 `requestBytes`（`Uint8Array` 檢視窗）給 `fetch` 時**：
   瀏覽器會看 `requestBytes.byteOffset` (100) 與 `requestBytes.byteLength` (34)，**精準只切出那 34 bytes** 放進 HTTP Body 送給後端 ✅。
2. **當你誤傳 `requestBytes.buffer`（底層 `ArrayBuffer`）給 `fetch` 時**：
   `ArrayBuffer` 本身不知道 `byteOffset` 和 `byteLength`，瀏覽器會把**整塊 8,192 bytes 的記憶體池（包含前面別筆訊息的殘留資料與後面的垃圾 byte）** 全部送給後端，導致後端 `ChatMessage.parseFrom()` 解出錯亂的欄位或直接拋出 `InvalidProtocolBufferException`（HTTP 400）❌！

### 必記口訣
- **送出給 `fetch` / `ws.send` 時**：永遠傳 **`Uint8Array` 本身**（`requestBytes as Uint8Array<ArrayBuffer>` 或 `new Uint8Array(requestBytes)`），**絕不傳 `.buffer`**！
