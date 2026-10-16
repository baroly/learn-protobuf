---
name: frontend-style
description: 前端程式碼規範 —— 適用於修改 frontend/ 下的 Vue 3 / TypeScript / Protobuf 檔案前載入。
---

# 前端程式碼規範（Vue 3 + TypeScript + protobufjs）

適用於 `frontend/` 目錄下的所有檔案。

---

## 註解

**原則：說明「作用」，不逐行複述程式碼。一律使用繁體中文。**

- 函式與 Composable 上方用一到三行說明作用（特別是二進位 `ArrayBuffer` 與 `Uint8Array` 轉換邊界）。
- 不寫複述程式碼的註解，不留被註解掉的舊程式碼。

---

## Protobuf 前端處理規範（`protobufjs`）

1. **`frontend/src/proto/` 為自動產生碼**：由 `npm run proto`（呼叫 `pbjs` 與 `pbts`）從 `../proto/chat.proto` 產生，**不進版控、禁止手改**。
2. **二進位型別邊界清楚**：
   - **編碼（Encode）**：使用 `ChatMessage.encode(message).finish()` 取得 `Uint8Array`，傳給 `fetch(..., { body: uint8Array })` 或 `ws.send(uint8Array)`。
   - **解碼（Decode）**：收到 `fetch` 的 `await res.arrayBuffer()` 或 WebSocket `event.data`（需設 `ws.binaryType = 'arraybuffer'`）後，**必須先轉為 `new Uint8Array(arrayBuffer)`** 再傳給 `ChatMessage.decode(uint8Array)`。
3. **`int64` (Long) 處理**：Protobuf 的 `int64`（如 `timestamp`）在 JS 中可能為 `number` 或 `Long` 物件，轉換為日期顯示前統一透過 `Number(msg.timestamp)` 轉型。

---

## 錯誤處理與日誌

- 網路請求失敗或二進位解碼失敗（`try / catch`）必須更新畫面上的錯誤狀態提示使用者，不可靜默吞掉錯誤。
- 開發除錯時可印出封包 `byteLength` 觀察二進位大小，正式提交前清理多餘的 `console.log`。

---

## 完成之前

依 `CLAUDE.md`：**產生碼更新且型別檢查與打包通過**才算完成。

```bash
cd frontend
npm run proto
npm run build
```
