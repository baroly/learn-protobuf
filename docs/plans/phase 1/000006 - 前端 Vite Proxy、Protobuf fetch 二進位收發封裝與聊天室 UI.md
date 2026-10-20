# 000006 - 前端 Vite Proxy、Protobuf `fetch` 二進位收發封裝與聊天室 UI

**狀態**：已完成（2026-10-20）：前後端 HTTP `application/x-protobuf` 二進位收發與淺藍色聊天室 UI 聯調通過
**日期**：2026-10-19
**前置**：`000004` 已核准、後端 `000005` 已完成（`POST /api/messages` 與 `GET /api/messages` 已就緒並通過 9 項測試）。

這份報告完成 **Phase 1（HTTP REST + Protobuf 二進位收發）的最後一哩路**：
1. **【步驟 1 — 由你親手實作核心通訊功能】**：
   - 在 `frontend/vite.config.ts` 設定 `/api` 開發伺服器代理（Proxy），將請求無縫轉發至後端 `http://localhost:8080`。
   - 建立 `frontend/src/api/chatApi.ts`，使用瀏覽器原生 `fetch` API 搭配 `protobufjs`，完整實作 `application/x-protobuf` 二進位的序列化發送（`Uint8Array`）與反序列化接收（`ArrayBuffer` ➔ `Uint8Array` ➔ `decode`），並計算每次收發的二進位 byte 長度與對等 JSON 長度供畫面展示。
2. **【步驟 2 — 由 AI 代寫淺藍色主題聊天室 UI】**：
   - 依你的指示（已記於 `docs/decisions.md`），當你完成步驟 1 的核心通訊模組後，由 AI 負責撰寫 `frontend/src/style.css` 與 `frontend/src/App.vue`（採用 `crypt-fiction` 專案的 `#4596FF` 淺藍色系、左側 `4px` 主色強調條與清爽白底卡片風格），串接你寫好的 `chatApi.ts`。

做完之後：
- 同時啟動後端（`mvn spring-boot:run`）與前端（`npm run dev`），打開瀏覽器就能在清爽的淺藍色聊天室介面輸入暱稱、發送訊息、拉取歷史訊息。
- 畫面側邊欄會即時顯示**最後一次 HTTP 請求/回應的 Protobuf 二進位大小 vs JSON 大小（節省百分比）**；打開 **F12 DevTools → Network** 面板，能親眼確認 `POST /api/messages` 與 `GET /api/messages` 的 `Content-Type` 皆為 `application/x-protobuf`，且 Payload 完完全全是二進位！

---

## 零、先定 2 個結構問題

### 1. ★ 瀏覽器 `fetch` 收發 Protobuf 二進位的型別轉換邊界（`Uint8Array` ↔ `ArrayBuffer`）

- **問題**：
  1. **接收端（Response）**：瀏覽器 `fetch` 的 `await response.arrayBuffer()` 回傳的是底層記憶體緩衝區 **`ArrayBuffer`**，但 `protobufjs` 的 `ChatMessage.decode(...)` 與 `ChatHistoryResponse.decode(...)` **只接受 `Uint8Array`**！如果直接把 `ArrayBuffer` 塞給 `decode()`，執行期與 TypeScript 都會報錯。
  2. **發送端（Request）與 TypeScript 6.0 型別檢查**：`ChatMessage.encode(msg).finish()` 回傳的型別是 `Uint8Array`（在 TS 5.7+ / 6.0 內部定義為 `Uint8Array<ArrayBufferLike>`）。而瀏覽器 DOM `fetch` 的 `body` 參數（`BodyInit`）要求的是 `ArrayBufferView<ArrayBuffer>`（不允許 `SharedArrayBuffer`），若直接寫 `body: requestBytes`，`vue-tsc -b` 嚴格檢查會報型別不相容。
- **選擇**：
  1. **發送時**：將 `encode(msg).finish()` 產出的 `requestBytes` 以 `body: new Uint8Array(requestBytes)` 傳入 `fetch`（明確建立綁定標準 `ArrayBuffer` 的檢視，零轉型警告且 100% 通過 `vue-tsc -b`）。
  2. **接收時**：固定遵循三步口訣：**`const buf = await response.arrayBuffer()` ➔ `const bytes = new Uint8Array(buf)` ➔ `ChatMessage.decode(bytes)`**。
- **為什麼**：這條邊界在 Phase 1（HTTP `fetch`）與 Phase 2（WebSocket `event.data`）完全一模一樣，在 `chatApi.ts` 把這個標準模式建立起來，Phase 2 寫 WebSocket 時就能直接套用。

### 2. ★ 核心通訊邏輯與 UI 畫面的切分（`src/api/chatApi.ts` vs `src/App.vue`）

- **問題**：如果把 `fetch`、`Uint8Array` 編解碼與 Vue 畫面狀態全部混在 `App.vue` 裡，一方面你還要處理你不感興趣的 HTML/CSS 細節，另一方面未來 Phase 2 也較難重用 API 函式。
- **選擇**：
  - 將所有與 HTTP Protobuf 二進位通訊相關的邏輯 100% 獨立封裝在 **`frontend/src/api/chatApi.ts`**（由你親手實作）。
  - `chatApi.ts` 對外只提供兩個乾淨的非同步函式：`sendMessageBinary(...)` 與 `fetchHistoryBinary()`，並一併回傳封包的二進位統計資訊（`PacketMetrics`：Protobuf byte 數、對照 JSON byte 數、原始 `Uint8Array` 預覽）。
  - `App.vue` 與 `style.css` 則單純呼叫這兩個函式並渲染淺藍色聊天室畫面（由 AI 代寫）。
- **連帶影響**：你在步驟 1 寫完 `vite.config.ts` 與 `src/api/chatApi.ts` 後，可以直接跑 `npx vue-tsc -b` 獨立驗證你的 TypeScript 程式碼 100% 零錯誤，接著交給 AI 完成步驟 2 畫面即可。

---

## 一、要改哪些檔案、各改什麼

所有檔案皆位於前端 `frontend/` 目錄下：

### 步驟 1：由使用者親手實作（核心功能與代理設定，見第零節第 1、2 項）

| 檔案 | 性質 | 改什麼 |
|---|---|---|
| `frontend/vite.config.ts` | 改寫 | 新增 `server.proxy` 設定，將 `/api` 開頭的請求轉發至後端 `http://localhost:8080` |
| `frontend/src/api/chatApi.ts` | 新增 | 封裝 `sendMessageBinary` (`POST /api/messages`) 與 `fetchHistoryBinary` (`GET /api/messages`)，處理 `application/x-protobuf` 標頭、`Uint8Array` / `ArrayBuffer` 轉換與封包體積統計 |

### 步驟 2：由 AI 代寫（淺藍色主題 UI 畫面與樣式，見第零節第 2 項）

| 檔案 | 性質 | 改什麼 |
|---|---|---|
| `frontend/src/style.css` | 改寫 | 替換 Vite 預設樣式，導入對齊 `crypt-fiction` 的淺藍色系全域變數（`--primary-color: #4596FF`、`--bg-color: #f5f5f5`、`--card-bg: #ffffff`、`--surface-bg: #f9f9f9`）與基礎排版 |
| `frontend/src/App.vue` | 改寫 | 實作淺藍色風格聊天室介面（頂部標題列、左側 Protobuf 封包即時監測資訊欄、右側訊息列表與底部發送列），串接 `chatApi.ts` |

---

## 二、對既有功能的衝擊與破壞性風險

- **資料遷移**：沒有。
- **相容性**：沿用 `src/proto/chat.js` 與 `src/proto/chat.d.ts`（不需修改 `.proto`），與後端 `000005` 的 `/api/messages` 路由與 `application/x-protobuf` 契約 100% 吻合。
- **既有行為的改變**：`App.vue` 從 Phase 0 的靜態編解碼展示頁升級為實際連往後端 Spring Boot 的互動式聊天室（同時在畫面左側保留並強化了二進位 vs JSON 的體積對比面板）。

### 已知風險

| 風險 | 緩解 |
|---|---|
| 後端伺服器未啟動時，前端發送請求會收到 Vite Proxy 的 `500` / `502` 錯誤 | 在 `chatApi.ts` 中檢查 `!response.ok` 並拋出包含 HTTP 狀態碼的明確錯誤訊息，由畫面顯示友善提示 banner |
| Protobuf `int64` (`timestamp`) 在 JS 中可能為 `Long` 物件或 `number` | 遵循 `.claude/skills/frontend-style/SKILL.md`，轉換時間字串前統一使用 `Number(msg.timestamp)` |

---

## 三、替代方案與取捨

### A. HTTP 客戶端選用：瀏覽器原生 `fetch` API（★ 建議） vs 安裝 `axios`

- **方案甲（★ 建議：使用瀏覽器原生 `fetch` API）**：
  - **優點**：
    1. **零額外套件依賴**，原生支援 `body: new Uint8Array(...)` 與 `await response.arrayBuffer()`。
    2. 轉換過程透明直接，最能學到瀏覽器底層 `ArrayBuffer` 與 `Uint8Array` 的關係。
- **方案乙（安裝 `axios` 並設定 `responseType: 'arraybuffer'`）**：
  - **為什麼排除**：`axios` 在瀏覽器底層依然是呼叫 `XMLHttpRequest` / `fetch`，多裝一個套件反而遮蔽了原生的二進位串流 API，且傳送 `Uint8Array` 時偶爾會有預設 `Content-Type` 覆蓋問題。

### B. 跨域處理方式：Vite `server.proxy`（★ 2026-10-17 於 `000004` 定案） vs 後端 CORS

- 已於 `000004` 定案採用 Vite `server.proxy`，避免自訂 `Content-Type: application/x-protobuf` 觸發多餘的 `OPTIONS` 預檢請求。

---

## 四、步驟拆解與驗證方式（含套件教學與完整程式碼藍圖）

| # | 誰做 | 做什麼 | 怎麼確認沒壞 |
|---|---|---|---|
| 1 | **使用者親手實作** | 修改 `frontend/vite.config.ts` 並新增 `frontend/src/api/chatApi.ts` | 於 `frontend/` 執行 `npx vue-tsc -b` 確認零型別錯誤 |
| 2 | **AI 代寫 UI 並共同驗收** | AI 改寫 `frontend/src/style.css` 與 `frontend/src/App.vue` 淺藍色聊天室畫面，啟動前後端聯調 | 1. `npm.cmd run build` 打包通過<br>2. 走過下方「瀏覽器人工驗收檢查表」第 1～4 項 |

---

### 📚 必讀教學：前端 `fetch` 二進位收發與 Vite Proxy 必記要點

#### 1. 瀏覽器二進位三劍客：`ArrayBuffer` vs `Uint8Array` vs `fetch`

在 Java 後端，二進位從頭到尾都叫 `byte[]`，非常單純；但在 JavaScript / 瀏覽器中，二進位被分成了兩個角色：

| 角色 | 它是什麼？ | 就像什麼？ | 誰用它？ |
|---|---|---|---|
| **`ArrayBuffer`** | **原始記憶體區塊（Raw Memory Buffer）**。它只是一塊固定長度的連續記憶體，**你不能直接用 `buf[0]` 讀寫裡面的 byte**！ | 一塊還沒畫格線的「空白硬碟磁區」 | `await response.arrayBuffer()`（`fetch` 回傳的東西）、WebSocket 的 `event.data` |
| **`Uint8Array`** | **8 位元無號整數檢視（View）**。它套在 `ArrayBuffer` 上面，把記憶體切成一個個 `0 ~ 255` 的 byte 格子，讓程式可以讀寫每個 byte。 | 套在磁區上的「1 byte 讀取尺」 | `protobufjs` 的 `encode().finish()`（產出）與 `decode(uint8Array)`（輸入） |

**★ 必記黃金轉換公式（寫 `fetch` 與 WebSocket 都靠這兩行）**：
- **要發送給後端時（Object ➔ `Uint8Array` ➔ `fetch`）**：
  ```ts
  // 1. protobufjs 編碼產出 Uint8Array
  const requestBytes: Uint8Array = ChatMessage.encode(msg).finish()
  // 2. 放進 fetch 的 body 送出（包一層 new Uint8Array 確保符合 TS 6.0 的 BodyInit 型別）
  const response = await fetch('/api/messages', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-protobuf',
      'Accept': 'application/x-protobuf',
    },
    body: new Uint8Array(requestBytes),
  })
  ```
- **從後端收到回應時（`fetch` ➔ `ArrayBuffer` ➔ `Uint8Array` ➔ Object）**：
  ```ts
  // 1. 先從 HTTP Response 拿出底層 ArrayBuffer（千萬別寫成 response.json()！）
  const arrayBuffer: ArrayBuffer = await response.arrayBuffer()
  // 2. ★ 關鍵一步：把 ArrayBuffer 包裝成 Uint8Array 檢視
  const responseBytes = new Uint8Array(arrayBuffer)
  // 3. 交給 protobufjs 解碼還原成強型別物件！
  const savedMessage = ChatMessage.decode(responseBytes)
  ```

---

#### 1-補充. 為什麼 `body` 要多包 `new Uint8Array(requestBytes)`？正式環境如何 Zero-Copy？以及 `protobufjs` 8KB 記憶體池冷知識（詳見 [docs/notes/06](../../notes/06%20-%20TypeScript%206.0%20Uint8Array%20%E6%B3%9B%E5%9E%8B%E9%99%90%E5%88%B6%E3%80%81Zero-Copy%20%E6%96%B7%E8%A8%80%E8%88%87%20protobufjs%208KB%20%E8%A8%98%E6%86%B6%E9%AB%94%E6%B1%A0%E9%99%B7%E9%98%B1.md)）

1. **為什麼 TypeScript 6.0 不讓你直接寫 `body: requestBytes`？**
   - 在純 JS 執行期，`requestBytes` 本身就已經是 `Uint8Array`，瀏覽器完全吃得下。
   - 但 TypeScript 5.7+ / 6.0 將 `Uint8Array` 改為泛型 `Uint8Array<底層記憶體>`，且瀏覽器 `fetch` 的 `BodyInit` 只收一般記憶體 `Uint8Array<ArrayBuffer>`，**拒收多執行緒共享記憶體 `SharedArrayBuffer`**。
   - 因為 `protobufjs` 生成的 `chat.d.ts` 寫的是舊式未帶泛型的 `finish(): Uint8Array`，TS 6.0 預設會把它推論成模糊的 `Uint8Array<ArrayBufferLike>`（懷疑它可能是 `SharedArrayBuffer`）而報錯。
2. **正式環境追求 Zero-Copy（零記憶體複製）怎麼寫？**
   - **絕不手改 `chat.js` / `chat.d.ts`**（因為重跑 `npm run proto` 就會被覆蓋）。
   - **練習 / 小封包**：寫 `body: new Uint8Array(requestBytes)`（建構子明確產出 `Uint8Array<ArrayBuffer>`，不用寫 `as`）。
   - **正式生產環境（Zero-Copy 零效能損耗）**：在 `chatApi.ts` 封裝層寫 **`body: requestBytes as Uint8Array<ArrayBuffer>`**（編譯成 JS 後 `as` 完全消失，執行期 0 複製）。
3. **💡 `protobufjs` 底層的「8KB 共用記憶體池（Buffer Pool）」冷知識（千萬別傳 `.buffer`！）**：
   - `protobufjs` 在執行 `encode(msg).finish()` 時，為了極致效能、避免每次發訊息都跟系統申請新記憶體，內部共用了一塊 **8 KB (8,192 bytes) 的大 `ArrayBuffer` 記憶體池**。
   - `finish()` 回傳給你的 `requestBytes`（例如 34 bytes），其實只是指著那塊 8 KB 大記憶體其中一小段的**檢視窗（Subarray View，帶有 `byteOffset` 與 `byteLength`）**。
   - 當你傳 `requestBytes`（`Uint8Array`）給 `fetch` 時，瀏覽器只會精準讀取檢視窗框起來的那 34 bytes ✅；但如果你寫成 `body: requestBytes.buffer`（直接拿底層的 `ArrayBuffer`），就會把**整塊 8,192 bytes 的共用記憶體池（包含上一次別筆訊息殘留的髒資料）** 全部當成 HTTP Body 送給後端，後端解析就會直接大爆炸 ❌！

---

#### 2. Vite `server.proxy` 運作原理（位於 `vite.config.ts`）

- 前端開發伺服器跑在 `http://localhost:5173`，後端 Spring Boot 跑在 `http://localhost:8080`。
- 我們在 `vite.config.ts` 設定：
  ```ts
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  }
  ```
- **發生了什麼事？**
  當你的 `chatApi.ts` 呼叫 `fetch('/api/messages')` 時，瀏覽器以為它是在跟同源的 `localhost:5173` 講話（因此完全沒有 CORS 跨域問題）；Vite 收到 `/api/messages` 後，會在背景偷偷幫你轉發給 `http://localhost:8080/api/messages`（剛好對應你後端 `application.yaml` 的 `context-path: /api` + `ChatController` 的 `@RequestMapping("messages")`！）。

---

### 🛠️ 步驟 1（由你親手實作）：`vite.config.ts` 與 `src/api/chatApi.ts`

#### 1-1. 修改 `frontend/vite.config.ts`

請打開 `frontend/vite.config.ts`，加上 `server.proxy` 設定：

```ts
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      // 將前端 /api 開頭的請求代理轉發至後端 Spring Boot (8080 埠)
      // 見 docs/plans/000004 第三節 B 與 docs/plans/000006 第四節
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
```

#### 1-2. 新增 `frontend/src/api/chatApi.ts`

請在 `frontend/src/` 下建立 `api` 資料夾，並新增 `chatApi.ts`：

```ts
import { demo } from '../proto/chat'

const { ChatMessage, ChatHistoryResponse, MessageType } = demo.chat

/** Protobuf 二進位專屬的 HTTP Media Type（對應後端 ChatController 的常數） */
const PROTOBUF_CONTENT_TYPE = 'application/x-protobuf'

/**
 * 紀錄單次 HTTP 請求/回應的二進位封包觀測數據，供教學介面展示 Protobuf 與 JSON 的體積差異
 */
export interface PacketMetrics {
  /** 動作描述（例如 POST /api/messages 或 GET /api/messages） */
  action: string
  /** 實際傳輸的 Protobuf 二進位 byte 長度 */
  protobufBytes: number
  /** 若改用對等 JSON 字串傳輸所需的 UTF-8 byte 長度 */
  jsonBytes: number
  /** 前 24 個 byte 的十進位陣列預覽 */
  bytePreview: number[]
}

/**
 * 透過 POST /api/messages 發送單筆 Protobuf 二進位聊天訊息，並解碼後端回傳的 ChatMessage。
 * 見 docs/plans/000006 第零節與第四節。
 */
export async function sendMessageBinary(
  sender: string,
  content: string,
  type: demo.chat.MessageType = MessageType.CHAT
): Promise<{ message: demo.chat.ChatMessage; metrics: PacketMetrics }> {
  // 1. 建立前端待發送的 ChatMessage 物件（id 與 timestamp 由後端伺服器統一產生）
  const outgoing = ChatMessage.create({
    sender,
    content,
    type,
  })

  // 2. 序列化為 Uint8Array 二進位陣列
  const requestBytes: Uint8Array = ChatMessage.encode(outgoing).finish()

  // 3. 發送 HTTP POST 二進位請求
  const response = await fetch('/api/messages', {
    method: 'POST',
    headers: {
      'Content-Type': PROTOBUF_CONTENT_TYPE,
      Accept: PROTOBUF_CONTENT_TYPE,
    },
    body: new Uint8Array(requestBytes),
  })

  if (!response.ok) {
    throw new Error(`發送訊息失敗 (HTTP ${response.status})`)
  }

  // 4. 取出回應的 ArrayBuffer 並轉成 Uint8Array，再交給 ChatMessage.decode 反序列化
  const responseBuffer: ArrayBuffer = await response.arrayBuffer()
  const responseBytes = new Uint8Array(responseBuffer)
  const savedMessage = ChatMessage.decode(responseBytes)

  // 5. 計算對照組 JSON 的 UTF-8 byte 長度供畫面比較
  const equivalentJson = JSON.stringify({
    id: savedMessage.id,
    sender: savedMessage.sender,
    content: savedMessage.content,
    timestamp: Number(savedMessage.timestamp),
    type: MessageType[savedMessage.type],
  })
  const jsonBytes = new TextEncoder().encode(equivalentJson).byteLength

  return {
    message: savedMessage,
    metrics: {
      action: 'POST /api/messages (Response)',
      protobufBytes: responseBytes.byteLength,
      jsonBytes,
      bytePreview: Array.from(responseBytes.slice(0, 24)),
    },
  }
}

/**
 * 透過 GET /api/messages 拉取後端記憶體中的歷史訊息列表（ChatHistoryResponse 二進位封包）。
 */
export async function fetchHistoryBinary(): Promise<{
  messages: demo.chat.ChatMessage.$Properties[]
  metrics: PacketMetrics
}> {
  const response = await fetch('/api/messages', {
    method: 'GET',
    headers: {
      Accept: PROTOBUF_CONTENT_TYPE,
    },
  })

  if (!response.ok) {
    throw new Error(`載入歷史訊息失敗 (HTTP ${response.status})`)
  }

  // 1. 取出回應的 ArrayBuffer 並轉成 Uint8Array，再用 ChatHistoryResponse.decode 反序列化
  const responseBuffer: ArrayBuffer = await response.arrayBuffer()
  const responseBytes = new Uint8Array(responseBuffer)
  const historyResponse = ChatHistoryResponse.decode(responseBytes)

  // 2. 計算對照組 JSON 的 UTF-8 byte 長度
  const equivalentJson = JSON.stringify({
    messages: historyResponse.messages.map((m) => ({
      id: m.id,
      sender: m.sender,
      content: m.content,
      timestamp: Number(m.timestamp),
      type: MessageType[m.type ?? 0],
    })),
  })
  const jsonBytes = new TextEncoder().encode(equivalentJson).byteLength

  return {
    messages: historyResponse.messages,
    metrics: {
      action: `GET /api/messages (${historyResponse.messages.length} 筆)`,
      protobufBytes: responseBytes.byteLength,
      jsonBytes,
      bytePreview: Array.from(responseBytes.slice(0, 24)),
    },
  }
}
```

---

### 🎨 步驟 2（由 AI 代寫）：淺藍色風格聊天室 UI（`src/style.css` 與 `src/App.vue`）

當你完成步驟 1 並告知我後，我會直接幫你寫好 `src/style.css` 與 `src/App.vue`，視覺設計對齊你指定的 `crypt-fiction/vue-frontend/src/views/IndexView.vue`：
- **配色變數**：
  - 主色 `--primary-color: #4596FF`（淺藍色）、hover 深藍 `#3778CC`、淺藍底高亮 `#ECF5FF`
  - 背景 `--bg-color: #f5f5f5`、卡片 `--card-bg: #ffffff`、次要區塊 `--surface-bg: #f9f9f9`
  - 文字 `--text-main: #333333`、`--text-light: #999999`、邊框 `--border-color: #eaeaea`
- **版面配置（雙欄式 `.container` 卡片）**：
  - **左側欄（Sidebar 寬 280px）**：採用 `IndexView.vue` 招牌的 `border-left: 4px solid var(--primary-color)` 標題列，放入「使用者設定（暱稱與訊息類型切換）」與「Protobuf 二進位封包觀測儀表板（顯示最近一次請求的 Protobuf bytes vs JSON bytes、節省比例與 `Uint8Array` 內容預覽）」。
  - **右側主內容區（Main Content）**：頂部顯示聊天室狀態與「重新載入歷史訊息 (`GET /api/messages`)」按鈕，中間為訊息氣泡列表（區分自己發送的淺藍色氣泡、他人訊息與系統 `JOIN`/`LEAVE` 徽章），底部為訊息輸入框與「發送 (`POST /api/messages`)」按鈕。

---

### 檢查表（人看畫面，完成步驟 2 後逐項確認）

1. **啟動後端與前端**：
   - 於 `demo/` 啟動後端 `mvn spring-boot:run`（監聽 `8080` 埠）。
   - 於 `frontend/` 啟動前端 `npm.cmd run dev`（開啟 `http://localhost:5173`）。
2. **檢查 1（初始載入 `GET /api/messages`）**：
   - 打開網頁與瀏覽器 **F12 DevTools → Network**，重新整理頁面，應看到一筆 `GET /api/messages` 請求，狀態碼 `200 OK`，Response Headers 的 `Content-Type` 為 `application/x-protobuf`。
3. **檢查 2（發送訊息 `POST /api/messages`）**：
   - 在畫面輸入暱稱（例如 `Leo`）與訊息內容（例如 `Hello Protobuf HTTP!`）並點擊發送：
   - 畫面上立即出現該筆訊息（帶有後端分配的 `msg-1` 與伺服器時間）。
   - 左側儀表板更新顯示這次 `POST` 的 Protobuf byte 數與節省比例。
   - DevTools Network 看到 `POST /api/messages` 的 Request Headers `Content-Type: application/x-protobuf`，且 Response 也是 `application/x-protobuf`。
4. **檢查 3（重新整理持久化於後端記憶體）**：
   - 按 F5 重新整理網頁，剛才發送的訊息依然會透過 `GET /api/messages` 從後端 `ChatMessageStore` 載入顯示在畫面上。

### 哪些東西要有測試

| 對象 | 要 | 為什麼 |
|---|---|---|
| 後端 `/api/messages` 的 Protobuf 二進位收發與異常處理 | 已於 `000005` 完成 | `ChatControllerTest` 已透過 `MockMvc` 完整覆蓋 |
| 前端 Vue 聊天室 UI 畫面與 DevTools Network 封包觀察 | 不寫自動化測試（走上方檢查表） | 依 `CLAUDE.md` 完成標準，UI 畫面跑一次與看 Network 面板最直接 |

### 預估

約 20 分鐘（步驟 1 你親手寫 `vite.config.ts` 與 `chatApi.ts` 約 10～15 分鐘；步驟 2 由 AI 寫入 UI 並驗證約 5 分鐘）。

---

## 五、不在本任務範圍

- 多分頁即時自動推播（Phase 1 為 HTTP REST 請求/回應模型，另一個分頁需按「重新載入」才會拉取新訊息；WebSocket 即時雙向廣播屬於接下來的 **Phase 2**）。

---

## 六、我自己決定、核准時可以推翻的

| 決定 | 推翻的話怎麼改 |
|---|---|
| `sendMessageBinary` 與 `fetchHistoryBinary` 除了回傳解碼後的 Protobuf 物件外，一併計算並回傳 `PacketMetrics`（Protobuf byte 數 vs JSON byte 數） | 這樣能讓聊天室左側邊欄即時顯示每次收發省下多少 % 的流量，更具教學價值；若你希望 `chatApi.ts` 只回傳純訊息物件、把統計邏輯放到 `App.vue` 裡，核准時告訴我即可 |

---

## 七、結果

**完成日期**：2026-10-20

- **步驟 1（使用者親手實作）**：
  - 完成 `frontend/vite.config.ts` 的 `/api` Proxy 設定（轉發至 `http://localhost:8080`）。
  - 完成 `frontend/src/api/chatApi.ts` 的 `sendMessageBinary` (`POST /api/messages`) 與 `fetchHistoryBinary` (`GET /api/messages`)，`npx vue-tsc -b` 零型別錯誤通過。
- **步驟 2（AI 代寫 UI 與共同驗收）**：
  - 完成 `frontend/src/style.css` 與 `frontend/src/App.vue`（對齊 `#4596FF` 淺藍色系雙欄卡片與封包觀測儀表板），`npm.cmd run build` 打包通過（2.10s）。
- **人工檢查表結果**：
  - ✅ 檢查 1（初始載入 `GET /api/messages`）：成功透過 `application/x-protobuf` 拉取歷史訊息並解碼顯示。
  - ✅ 檢查 2（發送訊息 `POST /api/messages`）：成功將 `ChatMessage` 編碼為 `Uint8Array` 發送並解碼後端回應，左側儀表板即時顯示 Protobuf vs JSON 體積比較與前 24 bytes 預覽。
  - ✅ 檢查 3（重新整理 / 手動更新持久化）：點擊重新載入或重整頁面可從後端 `ChatMessageStore` 拉取最新訊息（確認 HTTP 請求/回應模型需手動觸發更新，即時自動推播將於 Phase 2 WebSocket 實作）。
- **沒驗證到的東西**：
  - WebSocket 多分頁 `< 100 ms` 即時自動廣播（屬於 Phase 2 範圍）。
- **與原計畫不同的地方**：無。

