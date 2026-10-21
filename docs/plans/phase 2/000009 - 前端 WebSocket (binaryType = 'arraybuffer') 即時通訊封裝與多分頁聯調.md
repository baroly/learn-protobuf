# 000009 - 前端 WebSocket (`binaryType = 'arraybuffer'`) 即時通訊封裝與多分頁聯調

**狀態**：已完成（2026-10-21）
**日期**：2026-10-20
**前置**：`000007`（Phase 2 任務拆解）已核准、後端 `000008`（`ChatWebSocketHandler` 與 14 項測試）已完成。

這份報告是 **Phase 2（以及整個 `java-protobuf` 學習專案核心目標）的最後一哩路**：
1. **【步驟 1 — 由你親手實作核心 WebSocket 二進位通訊模組】**：
   - 在 `frontend/vite.config.ts` 的 `'/api'` Proxy 設定加上 `ws: true`，讓 Vite 開發伺服器支援將 WebSocket 握手與二進位幀代理轉發至後端 `ws://localhost:8080/api/ws/chat`。
   - 建立 `frontend/src/api/chatWs.ts`，使用瀏覽器原生 `WebSocket` API 搭配 `protobufjs`，設定關鍵的 **`ws.binaryType = 'arraybuffer'`**，實作連線建立、發送 Protobuf 二進位幀（`Uint8Array`）、以及收到後端廣播二進位幀（`ArrayBuffer` ➔ `Uint8Array` ➔ `ChatMessage.decode`）的完整封裝。
2. **【步驟 2 — 由 AI 代寫 UI 連線狀態整合並共同進行多分頁驗收】**：
   - 延續 `000006` 的分工模式（見 `docs/decisions.md`），當你完成步驟 1 的 `vite.config.ts` 與 `chatWs.ts` 後，由 AI 更新 `frontend/src/App.vue`，串接你的 `chatWs.ts`，在畫面上加上 WebSocket 連線狀態指示燈（已連線／未連線、連線/斷線切換按鈕），並將發送訊息改由 WebSocket 即時推播。

做完之後：
- 打開 **2 個（甚至 10 個）瀏覽器分頁**，分別輸入不同暱稱（例如分頁 A 是 `Leo`、分頁 B 是 `Tim`）。
- 當 `Leo` 在分頁 A 按下發送（或點擊連線/斷線觸發 `JOIN` / `LEAVE` 通知）時，**完全不需要按重新整理**，分頁 B 會在 **< 100 ms** 內即時跳出 `Leo` 的訊息氣泡，且左側儀表板同步刷新最新收到的 WebSocket 二進位封包剖析數據！

---

## 零、先定 2 個結構問題

### 1. ★ 瀏覽器 `WebSocket` 接收二進位的最大陷阱：`ws.binaryType = 'arraybuffer'`

- **問題**：
  當你在瀏覽器執行 `const ws = new WebSocket(url)` 時，瀏覽器預設的 `ws.binaryType` 是 **`"blob"`**（檔案型態的大型二進位物件，必須用非同步 `await blob.arrayBuffer()` 才能讀取），**而不是 `"arraybuffer"`**！
  如果忘記改設定，當後端廣播 `BinaryMessage` 過來觸發 `ws.onmessage = (event) => { ... }` 時，`event.data` 會是一個 `Blob` 物件；這時如果你直接寫 `new Uint8Array(event.data)`，JavaScript 不會報錯，但會生出一個**長度為 `0` 的空陣列**，接著 `ChatMessage.decode()` 就會解出一個所有欄位都是預設空值的幽靈訊息！
- **選擇**：
  在 `new WebSocket(...)` 實體化後的**下一行**，立刻明確設定：
  ```ts
  ws.binaryType = 'arraybuffer'
  ```
- **為什麼**：
  設為 `'arraybuffer'` 後，`onmessage` 的 `event.data` 就會直接是記憶體緩衝區 `ArrayBuffer`，可以**同步（不需 `await`）** 用我們在 Phase 1 已經練熟的三步口訣：
  `const bytes = new Uint8Array(event.data)` ➔ `ChatMessage.decode(bytes)` 瞬間解碼！

### 2. ★ HTTP 歷史載入（Phase 1）與 WebSocket 即時廣播（Phase 2）如何分工？重複訊息怎麼防？

- **問題**：
  1. 新打開一個瀏覽器分頁時，怎麼看到過去大家聊過的訊息？
  2. 如果某人透過 Phase 1 的 `POST /api/messages` 發送訊息，後端 `ChatController` 也會呼叫 `broadCast(saved)` 推播給 WebSocket，前端會不會自己加一次、收到廣播又加一次，變成重複兩筆？
- **選擇**：
  1. **初始載入走 HTTP GET，即時收發走 WebSocket**：網頁打開（`onMounted`）時，先呼叫你在 Phase 1 寫好的 `fetchHistoryBinary()` (`GET /api/messages`) 拉取最近 1,000 筆歷史紀錄；同時呼叫 `chatWs.ts` 建立 WebSocket 長連線。
  2. **根據訊息 `id` 去重（Deduplication）**：當收到 WebSocket 廣播解碼出 `ChatMessage` 時，先檢查畫面列表最後幾筆是否已經存在相同的 `msg.id`（例如 `"msg-5"`），若沒有才 `push` 進列表。
- **為什麼**：
  這樣既保留了 Phase 1 HTTP API 的實戰用途，又能保證無論從哪個通道發送訊息，畫面上永遠不會出現重複的訊息氣泡。

---

## 一、要改哪些檔案、各改什麼

所有檔案皆位於前端 `frontend/` 目錄下：

### 步驟 1：由使用者親手實作（WebSocket 代理與核心二進位通訊封裝）

| 檔案 | 性質 | 改什麼 |
|---|---|---|
| `frontend/vite.config.ts` | 改寫 | 在 `/api` proxy 設定中補上 `ws: true`，啟用 WebSocket 代理轉發 |
| `frontend/src/api/chatWs.ts` | 新增 | 封裝 `ChatWebSocketClient`（或連線管理函式），負責建立 `/api/ws/chat` 連線、設定 `binaryType = 'arraybuffer'`、編碼發送 `ChatMessage` 二進位幀、以及解碼後端廣播幀並計算 `PacketMetrics` |

### 步驟 2：由 AI 代寫（聊天室 UI 串接 WebSocket 與多分頁驗收）

| 檔案 | 性質 | 改什麼 |
|---|---|---|
| `frontend/src/App.vue` | 改寫 | 串接 `chatWs.ts`，新增頂部與側邊欄的 WebSocket 連線狀態徽章（🟢 已連線 / 🔴 已斷線）、連線切換按鈕，並實現多分頁免重整即時訊息渲染 |

---

## 二、對既有功能的衝擊與破壞性風險

- **資料遷移**：沒有。
- **相容性**：沿用 `proto/demo/chat/chat.proto` 與 `frontend/src/api/chatApi.ts`（`PacketMetrics` 介面直接從你寫好的 `chatApi.ts` 匯入共用！）。

### 已知風險

| 風險 | 緩解 |
|---|---|
| 使用者在 WebSocket 尚未連線成功（`readyState !== WebSocket.OPEN`）時點擊發送 | 在 `chatWs.ts` 的發送方法中檢查 `ws.readyState === WebSocket.OPEN`，若未連線則拋出明確錯誤提示使用者 |
| 元件卸載或關閉分頁時未關閉 WebSocket 連線 | 在 `App.vue` 的 `onBeforeUnmount` 呼叫 `disconnect()` 主動關閉連線 |

---

## 三、替代方案與取捨

### A. 前端 WebSocket 路徑寫法：`new WebSocket('/api/ws/chat')` 搭配 Vite Proxy `ws: true`（★ 建議） vs 寫死 `ws://localhost:8080/api/ws/chat`

- **方案甲（★ 建議：使用相對路徑 `/api/ws/chat` + Vite `ws: true`）**：
  - **優點**：現代瀏覽器（Chrome / Edge / Firefox）與 Vite 支援在 `new WebSocket('/api/ws/chat')`（或透過 `window.location.host` 組合 `ws://${window.location.host}/api/ws/chat`）走同源代理轉發，不會有跨埠設定散落各處的問題。為了相容所有瀏覽器版本，我們在藍圖中使用最穩健的 `` `ws://${window.location.host}/api/ws/chat` ``，透過 Vite 的 `5173` 埠自動代理到後端 `8080` 埠！
- **方案乙（在前端程式碼寫死 `ws://localhost:8080/api/ws/chat`）**：
  - **為什麼排除**：若未來更改後端埠號或部署至正式機，前端程式碼就得重改，不如統一由 `vite.config.ts` 的 `/api` Proxy 集中管理。

---

## 四、步驟拆解與驗證方式（含套件教學與完整程式碼藍圖）

| # | 誰做 | 做什麼 | 怎麼確認沒壞 |
|---|---|---|---|
| 1 | **使用者親手實作** | 修改 `frontend/vite.config.ts`（加 `ws: true`）並新增 `frontend/src/api/chatWs.ts` | 於 `frontend/` 執行 `npx.cmd vue-tsc -b` 確認零型別錯誤 |
| 2 | **AI 代寫 UI 串接並共同驗收** | AI 更新 `frontend/src/App.vue` 串接 `chatWs.ts`，啟動前後端進行雙分頁即時廣播測試 | 1. `npm.cmd run build` 打包通過<br>2. 走過下方「瀏覽器雙分頁即時廣播檢查表」第 1～4 項 |

---

### 📚 必讀教學：瀏覽器原生 `WebSocket` 二進位收發必記要點

#### 1. `fetch` (HTTP) vs `WebSocket` 的 Protobuf 二進位對照表

你在 `000006` 已經寫過 `chatApi.ts` 的 `fetch` 了，現在來看 `WebSocket` 有多像（甚至更簡單！）：

| 動作 | Phase 1：HTTP `fetch` (`chatApi.ts`) | Phase 2：WebSocket (`chatWs.ts`) |
|---|---|---|
| **建立連線與設定二進位模式** | Header 設 `'Content-Type': 'application/x-protobuf'` | `const ws = new WebSocket(url)`<br>**`ws.binaryType = 'arraybuffer'`**（★ 必寫！） |
| **發送（Encode ➔ Send）** | `const bytes = ChatMessage.encode(msg).finish()`<br>`fetch(url, { body: new Uint8Array(bytes) })` | `const bytes = ChatMessage.encode(msg).finish()`<br>**`ws.send(bytes)`**（直接送出 `Uint8Array`！） |
| **接收（Receive ➔ Decode）** | `const buf = await response.arrayBuffer()`<br>`const bytes = new Uint8Array(buf)`<br>`ChatMessage.decode(bytes)` | `ws.onmessage = (event: MessageEvent<ArrayBuffer>) => {`<br>  `const bytes = new Uint8Array(event.data)`<br>  `const msg = ChatMessage.decode(bytes)`<br>`}` |

#### 2. WebSocket 的 4 個核心事件與 `readyState` 常數

- **`ws.onopen`**：握手成功、通道打開時觸發（此時我們可以自動發送一筆 `MessageType.JOIN` 的二進位封包，告訴所有人「我進來聊天室了」！）。
- **`ws.onmessage`**：每當後端 `ChatWebSocketHandler.broadCast()` 推播二進位幀過來時觸發，`event.data` 就是 `ArrayBuffer`。
- **`ws.onclose`**：連線關閉時觸發。
- **`ws.onerror`**：連線發生異常時觸發。
- **`ws.readyState`**：檢查連線目前狀態，只有當 `ws.readyState === WebSocket.OPEN`（常數值為 `1`）時，才可以呼叫 `ws.send(...)`。

---

### 🛠️ 步驟 1（由你親手實作）：`vite.config.ts` 與 `src/api/chatWs.ts`

#### 1-1. 修改 `frontend/vite.config.ts`（加上 `ws: true`）

請打開 `frontend/vite.config.ts`，在 `'/api'` 裡面補上一行 `ws: true`：

```ts
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true, // ★ 新增這行：啟用 WebSocket 二進位連線代理轉發
      },
    },
  },
})
```

---

#### 1-2. 新增 `frontend/src/api/chatWs.ts`

請在 `frontend/src/api/` 下新增 `chatWs.ts`（注意看它直接重用了你在 `chatApi.ts` 定義好的 `PacketMetrics` 介面）：

```ts
import { demo } from '../proto/chat'
import type { PacketMetrics } from './chatApi'

const { ChatMessage, MessageType } = demo.chat

/** 收到後端廣播訊息時的回呼函式型別 */
export type OnMessageCallback = (
  message: demo.chat.ChatMessage,
  metrics: PacketMetrics
) => void

/** 連線狀態改變時的回呼函式型別 */
export type OnStatusChangeCallback = (connected: boolean, error?: string) => void

/**
 * 封裝瀏覽器原生 WebSocket 的 Protobuf 二進位收發客戶端。
 * 見 docs/plans/000009 第零節與第四節。
 */
export class ChatWebSocketClient {
  private ws: WebSocket | null = null
  private readonly onMessage: OnMessageCallback
  private readonly onStatusChange: OnStatusChangeCallback

  constructor(onMessage: OnMessageCallback, onStatusChange: OnStatusChangeCallback) {
    this.onMessage = onMessage
    this.onStatusChange = onStatusChange
  }

  /**
   * 建立連往 /api/ws/chat 的二進位 WebSocket 連線，並在連線成功時可選擇自動發送 JOIN 通知。
   */
  connect(initialSender?: string): void {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const wsUrl = `${protocol}//${window.location.host}/api/ws/chat`

    const socket = new WebSocket(wsUrl)
    // ★ 關鍵必設：將二進位接收型態從預設的 'blob' 改為 'arraybuffer'，才能同步轉成 Uint8Array 解碼
    socket.binaryType = 'arraybuffer'

    socket.onopen = () => {
      this.onStatusChange(true)
      // 若連線時有提供暱稱，自動發送一筆 JOIN 二進位封包通知所有分頁
      if (initialSender && initialSender.trim()) {
        this.sendMessage(initialSender.trim(), `${initialSender.trim()} 加入了聊天室`, MessageType.JOIN)
      }
    }

    socket.onmessage = (event: MessageEvent<ArrayBuffer>) => {
      // 1. 將收到的 ArrayBuffer 轉為 Uint8Array 檢視，再交給 ChatMessage.decode 反序列化
      const frameBytes = new Uint8Array(event.data)
      const decodedMsg = ChatMessage.decode(frameBytes)

      // 2. 計算對照組 JSON 的 UTF-8 byte 長度供儀表板展示
      const equivalentJson = JSON.stringify({
        id: decodedMsg.id,
        sender: decodedMsg.sender,
        content: decodedMsg.content,
        timestamp: Number(decodedMsg.timestamp),
        type: MessageType[decodedMsg.type],
      })
      const jsonBytes = new TextEncoder().encode(equivalentJson).byteLength

      this.onMessage(decodedMsg, {
        action: `WS Broadcast (${MessageType[decodedMsg.type]})`,
        protobufBytes: frameBytes.byteLength,
        jsonBytes,
        bytePreview: Array.from(frameBytes.slice(0, 24)),
      })
    }

    socket.onerror = () => {
      this.onStatusChange(false, 'WebSocket 二進位連線發生錯誤（請確認後端 8080 埠是否已啟動）')
    }

    socket.onclose = () => {
      this.ws = null
      this.onStatusChange(false)
    }

    this.ws = socket
  }

  /**
   * 編碼並發送單筆 Protobuf 二進位訊息幀。
   */
  sendMessage(
    sender: string,
    content: string,
    type: demo.chat.MessageType = MessageType.CHAT
  ): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error('WebSocket 尚未連線，無法發送二進位訊息')
    }

    const outgoing = ChatMessage.create({
      sender,
      content,
      type,
    })

    // 序列化為 Uint8Array 並透過 WebSocket Binary Frame 直接送出
    const outgoingBytes: Uint8Array = ChatMessage.encode(outgoing).finish()
    this.ws.send(outgoingBytes)
  }

  /**
   * 主動關閉 WebSocket 連線（關閉前可選擇發送 LEAVE 通知）。
   */
  disconnect(leavingSender?: string): void {
    if (!this.ws) return

    if (this.ws.readyState === WebSocket.OPEN && leavingSender && leavingSender.trim()) {
      try {
        this.sendMessage(leavingSender.trim(), `${leavingSender.trim()} 離開了聊天室`, MessageType.LEAVE)
      } catch {
        // 忽略關閉前的發送異常
      }
    }

    this.ws.close()
    this.ws = null
    this.onStatusChange(false)
  }
}
```

---

### 🎨 步驟 2（由 AI 代寫）：`App.vue` 整合 WebSocket 即時通訊與連線狀態

當你完成步驟 1 並告訴我後，我會幫你更新 `frontend/src/App.vue`：
- 頂部標題列升級為 `Phase 2 · WebSocket + HTTP Binary`，並加上即時連線狀態徽章（🟢 `WS 已連線 (/api/ws/chat)` / 🔴 `WS 未連線`）與「中斷連線 / 重新連線」按鈕（讓你隨時測試 `JOIN` 與 `LEAVE` 廣播）。
- 訊息發送優先走 `wsClient.sendMessage(...)`，當任何分頁發送訊息時，所有在線分頁透過 `onmessage` 即時收到二進位幀並自動滑動到底部！

---

### 檢查表（人看畫面，完成步驟 2 後逐項確認）

1. **重啟後端（載入新加的 WebSocket 端點）並啟動前端**：
   - **⚠️ 重要提醒**：因為後端在 `000008` 新增了 `WebSocketConfig` 與 `ChatWebSocketHandler`，請務必在 IDEA **重新啟動（Restart）** 後端 Spring Boot 應用程式！
   - 前端維持 `npm.cmd run dev`（或重啟以載入 `vite.config.ts` 的 `ws: true`）。
2. **檢查 1（WebSocket 二進位握手與自動 `JOIN` 廣播）**：
   - 打開瀏覽器分頁 A（`http://localhost:5173`），應看到右上角顯示「🟢 WS 已連線」，且聊天室自動跳出一筆 `JOIN` 系統通知：`Leo：Leo 加入了聊天室`。
   - 打開 **F12 DevTools → Network → 篩選 `WS`**，點擊 `chat` 連線 → **Messages** 分頁，可親眼看到傳送與接收的都是 **`Binary Message`**！
3. **檢查 2（雙分頁 `< 100 ms` 免手動更新即時廣播）**：
   - 並排打開**第 2 個瀏覽器分頁 B**（`http://localhost:5173`），將分頁 B 左側的暱稱改為 `Tim`。
   - 在分頁 B 輸入訊息 `Hi Leo! 這是 WebSocket 即時二進位推播！` 並按 Enter 發送。
   - **完全不用按重新整理**，觀察分頁 A 與分頁 B 是否在瞬間（`< 100 ms`）同時出現這筆訊息氣泡，且兩邊左側儀表板都更新顯示 `WS Broadcast (CHAT)` 的 Protobuf byte 數與節省比例！
4. **檢查 3（手動斷線與重連觸發 `LEAVE` / `JOIN`）**：
   - 在分頁 B 點擊「中斷 WS 連線」，觀察分頁 A 是否立即跳出橙色的 `LEAVE` 徽章：`Tim：Tim 離開了聊天室`！

### 哪些東西要有測試

| 對象 | 要 | 為什麼 |
|---|---|---|
| 後端 `ChatWebSocketHandler` 二進位解析、1003 拒絕文字幀與 10 執行緒併發廣播 | 已於 `000008` 完成 | 後端 14 項測試已 100% 覆蓋 |
| 前端 Vue 畫面與雙分頁 WebSocket 即時同步 | 不寫自動化測試（走上方檢查表） | 依 `CLAUDE.md` 完成標準，UI 與雙分頁互動直接開兩個瀏覽器視窗對看最直觀 |

### 預估

約 20 分鐘（步驟 1 你親手改 `vite.config.ts` 1 行與寫 `chatWs.ts` 約 10～15 分鐘；步驟 2 由 AI 更新 `App.vue` 並雙分頁驗收約 5 分鐘）。

---

## 五、不在本任務範圍

- 自動斷線重連計時器（保留手動「連線 / 中斷連線」按鈕，更方便你隨時點擊觀察 `JOIN` 與 `LEAVE` 二進位幀）。

---

## 六、我自己決定、核准時可以推翻的

| 決定 | 推翻的話怎麼改 |
|---|---|
| `connect()` 成功時自動發送一筆 `MessageType.JOIN` 二進位封包、點擊手動斷線時自動發送一筆 `MessageType.LEAVE` 封包 | 這樣一打開第 2 個分頁就能馬上在第一個分頁看到「某某某加入了聊天室」的即時廣播效果，而且能把 `.proto` 裡的 `JOIN (1)` 和 `LEAVE (2)` 枚舉完整用上；若你不希望連斷線時自動發通知，核准時告訴我即可 |

---

## 七、結果

- **完成日期**：2026-10-21
- **實際改動**：
  1. **步驟 1（使用者親手實作）**：
     - 修改 `frontend/vite.config.ts`，在 `/api` 代理設定加入 `ws: true`，啟用 WebSocket 握手與二進位幀代理轉發。
     - 新增 `frontend/src/api/chatWs.ts`，封裝 `ChatWebSocketClient` 類別：設定 `socket.binaryType = 'arraybuffer'`，實作 `connect`（含連線成功自動發送 `MessageType.JOIN`）、`onmessage`（`ArrayBuffer` ➔ `Uint8Array` ➔ `ChatMessage.decode` 反序列化與 `PacketMetrics` 統計）、`sendMessage`（`ChatMessage.encode(outgoing).finish()` ➔ `ws.send`）與 `disconnect`（關閉前自動發送 `MessageType.LEAVE`）。
  2. **步驟 2（AI 代寫 UI 串接與雙分頁驗收）**：
     - 更新 `frontend/src/App.vue`，串接 `ChatWebSocketClient`，新增頂部 WebSocket 連線狀態徽章（🟢 `WS 已連線 (/api/ws/chat)` / 🔴 `WS 未連線`）與連線/斷線切換按鈕，並以訊息 `id` 去重整合 HTTP 歷史訊息載入與 WebSocket 即時推播。
- **驗證結果**：
  - 前端型別檢查與打包：`npm.cmd run build`（`vue-tsc -b && vite build`）於 `734ms` 零錯誤通過。
  - 瀏覽器雙分頁實機聯調：開啟多個分頁測試 `JOIN` / `CHAT` / `LEAVE` 二進位幀推播，各分頁於 `< 100 ms` 內即時同步渲染且儀表板即時刷新封包統計，Phase 2 全部完成。
- **偏離與補充說明**：
  - `chatWs.ts` 中 `sendMessage` 使用 `this.ws.send(new Uint8Array(outgoingBytes))` 確保符合 TypeScript 6.0 嚴格 `ArrayBuffer` 泛型簽章，且避免直接暴露 `protobufjs` 底層 8KB 共用記憶體池；`subarray(0, 24)` 用於擷取前 24 bytes 預覽。
