# 03 - protobufjs 靜態模組生成 (pbjs 與 pbts) 與 TypeScript 型別細節

在前端 Vite (ES Module) + Vue 3 + TypeScript 環境下，使用 `protobufjs` 與 `protobufjs-cli` 將 `.proto` 編譯為靜態模組時，有三個必記的實戰細節。

---

## 1. 為什麼 `pbjs` 必須加 `-w es6 --es6`？

`protobufjs-cli` 的 `pbjs -t static-module` 預設產出的是 CommonJS (`module.exports`) 包裝格式，直接放進 Vite (原生 ES Module) 專案中 `import` 會報 `does not provide an export named ...` 錯誤。

標準的跨平台 `package.json` 腳本寫法如下：

```json
"proto": "node -e \"fs.mkdirSync('src/proto', {recursive: true})\" && pbjs -t static-module -w es6 --es6 ../proto/demo/chat/chat.proto -o src/proto/chat.js && pbts -o src/proto/chat.d.ts src/proto/chat.js"
```

| 區段 / 參數 | 作用 |
|---|---|
| `node -e "fs.mkdirSync('src/proto', {recursive: true})"` | 使用 Node.js 內建 API 確保輸出目錄存在，避免 Windows PowerShell 與 Unix `mkdir -p` 語法差異導致 `pbjs` 找不到父目錄報錯 |
| `-t static-module` | 將 `.proto` 編譯為靜態 JS 編解碼函式（不需在瀏覽器執行期載入與解析 `.proto` 文字檔） |
| `-w es6 --es6` | 使用 ES6 模組包裝並啟用具名匯出（Named Export，例如 `export const demo = ...`），讓 Vite / TS 可直接 `import { demo } from './proto/chat'` |
| `pbts -o src/proto/chat.d.ts src/proto/chat.js` | **注意輸入檔是剛生成的 `chat.js` 而非 `.proto`**。`pbts` 會解析 `chat.js` 內的 JSDoc 註解並轉成 TypeScript `.d.ts` 型別宣告 |

---

## 2. `repeated` 陣列元素的 `$Properties` 可空型別（`string | null | undefined`）

在 `protobufjs` v8 產出的 `chat.d.ts` 中，訊息類別（如 `ChatMessage`）除了類別本身外，還會產生一個代表「初始化屬性袋」的介面 `ChatMessage.$Properties`（舊稱 `IChatMessage`）：

```ts
namespace ChatMessage {
    interface $Properties {
        id?: (string|null);
        sender?: (string|null);
        content?: (string|null);
        timestamp?: (number|Long|null);
        type?: (demo.chat.MessageType|null);
    }
}
```

而外層容器（如 `ChatHistoryResponse`）的 `repeated ChatMessage messages` 欄位，在 `.d.ts` 中的型別是：

```ts
messages: demo.chat.ChatMessage.$Properties[];
```

### 踩坑點與解法

因為 `messages` 陣列元素的每個屬性型別都包含 `null`（例如 `msg.id` 為 `string | null | undefined`），在 Vue 3 模板寫 `v-for` 時：

```html
<!-- ❌ vue-tsc -b 會報錯：Type 'string | null | undefined' is not assignable to type 'PropertyKey | undefined' -->
<li v-for="msg in decodedHistory.messages" :key="msg.id">

<!-- ✅ 使用 ?? 空值合併運算子排除 null -->
<li v-for="msg in decodedHistory.messages" :key="msg.id ?? ''">
```

---

## 3. `encode().finish()`、展開 `ArrayBuffer` 與 `int64` 轉型口訣

1. **`encode` 完一定要 `.finish()`**：`ChatMessage.encode(msg)` 回傳的是 `Writer` 緩衝區，必須呼叫 `.finish()` 才會產出 `Uint8Array`。
2. **`ArrayBuffer` 要包一層 `new Uint8Array(...)`**：日後從 `fetch` (`await res.arrayBuffer()`) 或 WebSocket (`event.data`) 收到的是原始 `ArrayBuffer`，傳給 `decode` 前必須寫 `ChatMessage.decode(new Uint8Array(buf))`。
3. **`int64` 轉 `Number(...)`**：Protobuf 的 `int64` 在 JS 型別為 `number | Long`，傳給 `new Date()` 前先寫 `Number(msg.timestamp)`。
