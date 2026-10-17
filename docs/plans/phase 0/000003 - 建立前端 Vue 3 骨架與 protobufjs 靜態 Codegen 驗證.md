# 000003 - 建立前端 Vue 3 骨架與 protobufjs 靜態 Codegen 驗證

**狀態**：已完成（2026-10-17）
**日期**：2026-10-16
**前置**：`000002` 已完成（`proto/demo/chat/chat.proto` 已建立，後端 Maven 編譯與序列化測試通過）。

這份報告建立前端 `frontend/`（Vue 3 + TypeScript + Vite）專案骨架，引入前端處理 Protobuf 的核心套件 `protobufjs` 與 `protobufjs-cli`，配置 `npm run proto` 自動從 `../proto/demo/chat/chat.proto` 產出靜態 JS 模組與 TypeScript 型別定義檔（`.d.ts`），並寫一段最小編解碼驗證程式確認前端也能把 `ChatMessage` 編碼成 `Uint8Array` 二進位陣列再解碼回來。

做完之後：
1. 在 `frontend/` 執行 `npm run proto`，會自動從 `../proto/demo/chat/chat.proto` 生出 `src/proto/chat.js` 與 `src/proto/chat.d.ts`。
2. 在 `frontend/` 執行 `npm run build`，TypeScript 型別檢查與 Vite 打包完全通過。
3. 啟動 `npm run dev` 打開瀏覽器（或看 Console），能看到前端使用 `protobufjs` 將 `ChatMessage` 序列化為 `Uint8Array`（同樣是 34 bytes 左右）並成功反序列化還原！

---

## 零、先定 2 個結構問題

### 1. ★ `pbjs` 靜態模組在 Vite (ES Module) 環境下的輸出格式參數

- **問題**：`protobufjs-cli` 的 `pbjs -t static-module` 預設產出的是 CommonJS wrapper，而 Vite 在開發期與打包期原生使用 ES Module (`import / export`)。若不指定 wrapper 與預設匯出格式，在 TypeScript 中 `import` 會遇到 ` does not provide an export named ...` 錯誤。
- **選擇**：在 `package.json` 的 `proto` 腳本使用由 `&&` 串聯的 3 段命令：
  `node -e "fs.mkdirSync('src/proto', {recursive: true})" && pbjs -t static-module -w es6 --es6 ../proto/demo/chat/chat.proto -o src/proto/chat.js && pbts -o src/proto/chat.d.ts src/proto/chat.js`
  - **第 1 段（跨平台建目錄）**：`node -e "fs.mkdirSync('src/proto', {recursive: true})"` 呼叫 Node.js 確保 `src/proto` 存在（若已存在不報錯）。
  - **第 2 段（`pbjs` 產出 JS 靜態模組）**：`-t static-module` 指定編譯為靜態 JS 程式碼；`-w es6 --es6` 指定使用 ES6 模組包裝與具名匯出（`export const demo = ...`）；`-o src/proto/chat.js` 指定輸出檔案。
  - **第 3 段（`pbts` 產出 TS 型別檔）**：讀取上一步 `src/proto/chat.js` 內的 JSDoc 註解，輸出 `src/proto/chat.d.ts` 型別定義檔。
- **為什麼**：`-w es6 --es6` 會指示 `pbjs` 直接輸出標準的 ES6 `export const demo = ...` 語法，讓 Vite 與 TypeScript 可以直接 `import { demo } from './proto/chat'` 零設定無縫對接。
- **連帶影響**：因為你的 `chat.proto` 宣告了 `package demo.chat;`，在前端匯入時的命名空間路徑為 `demo.chat.ChatMessage`、`demo.chat.MessageType`、`demo.chat.ChatHistoryResponse`。

### 2. 前端產生碼目錄 `frontend/src/proto/` 的版控策略

- **問題**：`src/proto/chat.js` 與 `src/proto/chat.d.ts` 要不要放進 Git？
- **選擇**：放在 `frontend/src/proto/`，已列入 `.gitignore` **不進版控、禁止手動修改**。
- **為什麼**：與後端 `demo/target/generated-sources/protobuf/` 對齊，維持 `proto/demo/chat/chat.proto` 為唯一真實來源。

---

## 一、要改哪些檔案、各改什麼

### 前端專案骨架與建置設定（第零節第 1、2 項）

| 檔案 | 性質 | 改什麼 |
|---|---|---|
| `frontend/`（Vite 模板初始化） | 新增 | 使用 `npm create vite@latest frontend -- --template vue-ts` 建立 Vue 3 + TS 專案 |
| `frontend/package.json` | 改寫 | 安裝 `protobufjs`（dependencies）與 `protobufjs-cli`（devDependencies），並在 `scripts` 新增 `"proto"` 指令 |
| `frontend/src/App.vue` | 改寫 | 撰寫最小 Protobuf 編解碼驗證（呼叫 `ChatMessage.create`、`encode().finish()`、`decode()` 並在畫面與 Console 顯示 `Uint8Array` 結果） |

---

## 二、對既有功能的衝擊與破壞性風險

- **資料遷移**：沒有。
- **相容性**：前端與後端共用同一個 `proto/demo/chat/chat.proto`，欄位編號 `1~5` 完全一致，產出的 `Uint8Array` 與後端 Java `toByteArray()` 二進位格式 100% 相容。
- **既有行為的改變**：沒有（首次建立 `frontend/` 目錄，完全不影響 `demo/` 後端）。

### 已知風險

| 風險 | 緩解 |
|---|---|
| Windows PowerShell 下若 `src/proto` 資料夾尚未建立，`pbjs -o src/proto/chat.js` 可能因找不到父目錄而報錯 | 在 `package.json` 的 `proto` script 開頭先用 `node -e "fs.mkdirSync('src/proto', {recursive: true})"` 確保目錄存在，跨平台都不會壞 |
| TypeScript `erasableSyntaxOnly` 或嚴格檢查對產生碼 `.d.ts` 報警 | `tsconfig.app.json` 預設開啟 `skipLibCheck: true`，會自動略過對 `.d.ts` 內部語法的過度挑剔，並保留完整的型別推導 |

---

## 三、替代方案與取捨

### A. `protobufjs` 靜態生成 `pbjs + pbts`（★ 建議） vs 執行期動態 `protobuf.load("chat.proto")`

- **方案甲（★ 建議：`pbjs + pbts` 靜態生成）**：
  - 建置期就轉成 `.js` 與 `.d.ts`，執行期更快、體積更小，且寫 Vue + TS 時打 `ChatMessage.` 就有完整的欄位與型別自動補全。
- **方案乙（動態載入 `.proto`）**：
  - 排除理由：瀏覽器每次開啟都要額外發 HTTP 請求下載 `.proto` 文字檔並在瀏覽器裡 parse，而且完全沒有 TypeScript 編譯期型別檢查，寫錯欄位名要到執行期才會爆開。

---

## 四、步驟拆解與驗證方式（含套件教學與完整程式碼藍圖）

| # | 做什麼 | 怎麼確認沒壞 |
|---|---|---|
| 1 | 建立 `frontend/` 專案並安裝 `protobufjs` 與 `protobufjs-cli` | `frontend/node_modules/protobufjs` 安裝成功 |
| 2 | 在 `frontend/package.json` 新增 `npm run proto` 並執行 | `frontend/src/proto/chat.js` 與 `chat.d.ts` 成功生成 |
| 3 | 在 `frontend/src/App.vue` 撰寫 `Uint8Array` 編解碼驗證 | 執行 `npm run build` 通過，且 `npm run dev` 畫面顯示編解碼後的 `Uint8Array` 長度與還原物件 |

---

### 📚 必讀教學：本報告引入的 2 個前端套件詳解

#### 套件 1：`protobufjs`（前端執行期核心庫）

- **套件定位**：
  對應後端的 `protobuf-java`。在瀏覽器與 Node.js 環境中，JavaScript 原生只有 `ArrayBuffer` 與 `Uint8Array`（相當於 Java 的 `byte[]`），`protobufjs` 提供了高效的 `Writer`（二進位寫入器）與 `Reader`（二進位讀取器），負責把 JS 物件壓縮成 `Uint8Array`、以及把 `Uint8Array` 解析回 JS 物件。
- **常用功能與 API（跟 Java 對照著記超好懂！）**：
  1. **`ChatMessage.create({ ... })`（建立訊息物件 —— 對應 Java 的 `newBuilder()...build()`）**：
     接收一個普通的 JS 物件（符合 `IChatMessage` 介面），建立出帶有預設值的 `ChatMessage` 實例。
     ```ts
     const msg = ChatMessage.create({
       id: 'msg-001',
       sender: 'Leo',
       content: 'Hello Tim',
       timestamp: Date.now(),
       type: MessageType.JOIN
     });
     ```
  2. **`ChatMessage.encode(msg).finish()`（序列化為二進位 `Uint8Array` —— 對應 Java 的 `msg.toByteArray()`）**：
     - `ChatMessage.encode(msg)` 會回傳一個內部的 `protobuf.Writer` 緩衝區（方便你在進階場景連續寫入多個區塊）。
     - **一定要接著呼叫 `.finish()`**，它才會把緩衝區打包並回傳最終的 **`Uint8Array`**！這個 `Uint8Array` 就可以直接塞進 `fetch` 的 `body` 或 WebSocket 的 `ws.send()`。
  3. **`ChatMessage.decode(uint8Array)`（從二進位反序列化 —— 對應 Java 的 `ChatMessage.parseFrom(bytes)`）**：
     - 接收一個 **`Uint8Array`**，解析並還原成 `ChatMessage` 實例。
     - ⚠️ **必記要點（超常踩的坑）**：瀏覽器的 `await response.arrayBuffer()` 或 WebSocket 的 `event.data` 拿到的型別是 **`ArrayBuffer`**（原始記憶體區塊），**不能直接傳給 `decode()`**！必須先用 **`new Uint8Array(arrayBuffer)`** 包一層轉成 `Uint8Array` 視圖，再傳給 `ChatMessage.decode(new Uint8Array(arrayBuffer))`。
  4. **`ChatMessage.verify(plainObj)`（選用：檢查欄位型別是否合法）**：
     如果合法回傳 `null`，如果不合法回傳錯誤描述字串。
- **應該記住的要點（地雷與特性）**：
  1. **64 位元整數（`int64 timestamp`）在 JS 的型別**：
     JavaScript 的 `number`（雙精度浮點數）最大安全整數是 $2^{53} - 1$（`9,007,199,254,740,991`），而 Protobuf 的 `int64` 是 $2^{63} - 1$。因此 `protobufjs` 解碼出的 `timestamp` 在型別定義上是 `number | Long`。當我們要把它丟進 `new Date(...)` 轉成時間字串時，養成習慣寫 **`Number(msg.timestamp)`** 就能安全轉成數字！
  2. **忘記呼叫 `.finish()`**：
     只寫 `ChatMessage.encode(msg)` 拿到的是 `Writer` 物件而不是 `Uint8Array`，直接拿去傳送會送出 `[object Object]` 導致後端解析失敗，口訣是 **「`encode` 完一定要 `.finish()`」**。

---

#### 套件 2：`protobufjs-cli`（開發期程式碼生成工具 `pbjs` 與 `pbts`）

- **套件定位**：
  對應後端的 `protoc-jar-maven-plugin`。它安裝在 `devDependencies`（不會打包進最終給瀏覽器的網頁），提供兩個命令列工具：
  1. **`pbjs`**：讀取 `.proto` 檔，生成靜態的 JavaScript 編解碼程式碼（`src/proto/chat.js`）。
  2. **`pbts`**：讀取剛生成的 `chat.js` 裡面的 JSDoc 註解，自動生成 TypeScript 型別宣告檔（`src/proto/chat.d.ts`）。
- **常用參數（為什麼這樣下指令）**：
  - `-t static-module`：指定目標（target）為靜態程式碼模組（效能最好、不需在瀏覽器解析 `.proto`）。
  - `-w es6 --es6`：指定外層包裝（wrapper）為 ES6 模組語法，才能被 Vite 直接 `import`。
  - `-o src/proto/chat.js`：指定輸出檔案路徑。

---

### 🛠️ 親手實作藍圖（共 3 步）

#### 步驟 1：在根目錄下初始化 `frontend` 並安裝套件

請打開終端機，在專案根目錄 `E:\SynologyDrive\workspace\learn\java-protobuf` 下依序執行：

```bash
# 1. 使用 Vite 官方 vue-ts 模板建立 frontend 資料夾（若詢問是否立即安裝並啟動，可選 No，我們自己裝）
npm create vite@latest frontend -- --template vue-ts

# 2. 進入 frontend 目錄
cd frontend

# 3. 安裝原本的基礎依賴
npm install

# 4. 安裝 Protobuf 執行期套件 (protobufjs) 與開發期 CLI (protobufjs-cli)
npm install protobufjs
npm install -D protobufjs-cli
```

---

#### 步驟 2：在 `frontend/package.json` 加入 `"proto"` 指令並執行

打開 `frontend/package.json`，在 `"scripts"` 區塊加上 `"proto"` 這一行：

```json
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc -b && vite build",
    "preview": "vite preview",
    "proto": "node -e \"fs.mkdirSync('src/proto', {recursive: true})\" && pbjs -t static-module -w es6 --es6 ../proto/demo/chat/chat.proto -o src/proto/chat.js && pbts -o src/proto/chat.d.ts src/proto/chat.js"
  },
```

接著在 `frontend/` 目錄下執行：

```bash
npm run proto
```

執行完後，檢查 `frontend/src/proto/` 資料夾，應該會看到 `chat.js` 與 `chat.d.ts` 兩個檔案生成出來！

---

#### 步驟 3：改寫 `frontend/src/App.vue` 驗證前端二進位編解碼

打開 `frontend/src/App.vue`，替換為以下程式碼，親手練習 `create`、`encode(...).finish()` 與 `decode`：

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { demo } from './proto/chat'

// 從自動生成的命名空間 demo.chat 解構出我們定義的類別與枚舉
const { ChatMessage, ChatHistoryResponse, MessageType } = demo.chat

// 1. 建立單筆訊息（對應 Java 的 ChatMessage.newBuilder()...build()）
const originalMessage = ChatMessage.create({
  id: 'msg-001',
  sender: 'Leo',
  content: 'Hello Tim',
  timestamp: Date.now(),
  type: MessageType.JOIN
})

// 2. 序列化成 Uint8Array 二進位陣列（對應 Java 的 message.toByteArray()）
// 必記：encode() 之後一定要呼叫 .finish() 才會拿到 Uint8Array！
const binaryBytes: Uint8Array = ChatMessage.encode(originalMessage).finish()

// 對照組：同樣資料轉成 JSON 字串的 UTF-8 byte 長度
const jsonString = JSON.stringify({
  id: originalMessage.id,
  sender: originalMessage.sender,
  content: originalMessage.content,
  timestamp: Number(originalMessage.timestamp),
  type: 'JOIN'
})
const jsonBytesLength = new TextEncoder().encode(jsonString).byteLength

// 3. 從 Uint8Array 反序列化還原為物件（對應 Java 的 ChatMessage.parseFrom()）
const decodedMessage = ChatMessage.decode(binaryBytes)

// 4. 測試 ChatHistoryResponse (repeated 陣列欄位)
const historyPacket = ChatHistoryResponse.create({
  messages: [
    originalMessage,
    ChatMessage.create({
      id: 'msg-002',
      sender: 'Tim',
      content: 'Hi Leo! 收到二進位封包！',
      timestamp: Date.now(),
      type: MessageType.CHAT
    })
  ]
})
const historyBytes = ChatHistoryResponse.encode(historyPacket).finish()
const decodedHistory = ChatHistoryResponse.decode(historyBytes)

const byteListPreview = ref(Array.from(binaryBytes).join(', '))
</script>

<template>
  <main style="max-width: 680px; margin: 2rem auto; font-family: sans-serif; line-height: 1.6;">
    <h1>Phase 0：前端 Protobuf (protobufjs) 編解碼驗證</h1>

    <section style="padding: 1rem; border: 1px solid #ccc; border-radius: 8px; margin-bottom: 1rem;">
      <h2>1. 二進位體積比較</h2>
      <p><strong>Protobuf Uint8Array 長度：</strong> {{ binaryBytes.byteLength }} bytes</p>
      <p><strong>對等 JSON 字串長度：</strong> {{ jsonBytesLength }} bytes</p>
      <p><strong>Uint8Array 內容：</strong> <code>[{{ byteListPreview }}]</code></p>
    </section>

    <section style="padding: 1rem; border: 1px solid #ccc; border-radius: 8px; margin-bottom: 1rem;">
      <h2>2. 解碼還原後的單筆 ChatMessage</h2>
      <ul>
        <li><strong>ID：</strong> {{ decodedMessage.id }}</li>
        <li><strong>發送者：</strong> {{ decodedMessage.sender }}</li>
        <li><strong>內容：</strong> {{ decodedMessage.content }}</li>
        <li><strong>時間：</strong> {{ new Date(Number(decodedMessage.timestamp)).toLocaleString() }}</li>
        <li><strong>類型枚舉值：</strong> {{ decodedMessage.type }} ({{ MessageType[decodedMessage.type] }})</li>
      </ul>
    </section>

    <section style="padding: 1rem; border: 1px solid #ccc; border-radius: 8px;">
      <h2>3. 解碼還原後的 ChatHistoryResponse ({{ decodedHistory.messages.length }} 筆)</h2>
      <ul>
        <li v-for="msg in decodedHistory.messages" :key="msg.id">
          [{{ msg.id }}] <strong>{{ msg.sender }}</strong>: {{ msg.content }}
        </li>
      </ul>
    </section>
  </main>
</template>
```

---

### 檢查表（人看畫面與建置）

| # | 做什麼 | 應該看到 |
|---|---|---|
| 1 | 執行 `npm run proto` | `frontend/src/proto/chat.js` 與 `chat.d.ts` 產生成功，無報錯 |
| 2 | 執行 `npm run build` | `vue-tsc -b` 型別檢查與 `vite build` 打包全綠通過 |
| 3 | 執行 `npm run dev` 並開啟瀏覽器 | 畫面顯示 Protobuf 長度（約 34 bytes）小於 JSON（約 93 bytes），且還原後的單筆訊息與 2 筆歷史列表正確顯示 |

### 哪些東西要有測試

| 對象 | 要 | 為什麼 |
|---|---|---|
| TypeScript 型別契約與靜態打包（`npm run build`） | **一定要** | 確保 `pbts` 產出的 `.d.ts` 與 Vite ESM 匯入無型別或打包錯誤 |
| Vue 驗證畫面 | 不要（`CLAUDE.md`） | 用上方檢查表 1–3 驗證 |

### 預估

約 20 分鐘。

---

## 五、不在本任務範圍

- 前端發送 `fetch` HTTP 請求給後端（屬於 **Phase 1**）。
- 聊天室輸入框與訊息列表 UI（屬於 **Phase 1**）。

---

## 六、我自己決定、核准時可以推翻的

| 決定 | 推翻的話怎麼改 |
|---|---|
| 前端目錄命名為 `frontend/` | 若你想取其他名字（如 `web/` 或 `chat-ui/`），在步驟 1 `npm create vite@latest` 時改資料夾名稱即可 |


---

## 七、結果

**完成日期**：2026-10-17

| 步驟 | 結果 |
|---|---|
| 1 | 建立 `frontend/`（Vite 8.3.4 + Vue 3.5.42 + TypeScript 6.0.2），並安裝 `protobufjs` (^8.8.1) 與 `protobufjs-cli` (^2.7.1) |
| 2 | 在 `frontend/package.json` 新增 `npm run proto` 並執行，成功從 `../proto/demo/chat/chat.proto` 產出 `src/proto/chat.js` 與 `src/proto/chat.d.ts`（282 行） |
| 3 | 完成 `frontend/src/App.vue` 編解碼驗證（`ChatMessage` 與 `ChatHistoryResponse` 的 `create`、`encode().finish()`、`decode()`），執行 `npm run build`（`vue-tsc -b && vite build`）於 **85 ms** 全綠通過（產出 bundle `116.75 kB` / gzip `39.16 kB`） |

### 檢查表結果

| # | 做什麼 | 結果 |
|---|---|---|
| 1 | 執行 `npm run proto` | 通過，`frontend/src/proto/chat.js` 與 `chat.d.ts` 產生成功，無報錯 |
| 2 | 執行 `npm run build` | 通過，`vue-tsc -b` 型別檢查與 `vite build` 打包全綠通過 |
| 3 | `App.vue` 二進位編解碼驗證 | 通過，單筆與 `ChatHistoryResponse` 多筆訊息皆可正常編碼為 `Uint8Array` 並反序列化還原 |

### 實作中發現的技術細節

- **`protobufjs` v8 的 `$Properties` 可空欄位型別**：`pbts` 產生的 `ChatHistoryResponse.messages` 元素型別為 `demo.chat.ChatMessage.$Properties`，其欄位型別皆為選填且可為 `null`（例如 `id?: (string|null)`）。在 Vue 3 `v-for` 綁定 `:key` 時，因 `:key` 不接受 `null`，需使用空值合併運算子 `:key="msg.id ?? ''"` 才能通過 `vue-tsc -b` 嚴格檢查（詳見 `docs/notes/03`）。

### 沒驗證到的

- 尚未與後端 `demo/` 進行跨行程 HTTP / WebSocket 實際二進位封包收發（將於 **Phase 1** 驗證）。

### 與計畫不同的地方

- 無（完全依計畫完成）。
