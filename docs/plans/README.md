# 開發報告

每一次動工前的報告放在這個資料夾。**核准之後才開始寫程式。** 寫法見 `.claude/skills/plan-report/`。

## 目錄與命名

報告**按階段分子目錄**，子目錄名就是階段名：

```
docs/plans/
  README.md          這份索引
  phase 0/
    000001 - Phase 0 任務拆解.md
    000002 - 定義共用 chat.proto 與後端 Maven Protobuf 編譯＋序列化單元測試.md
    000003 - 建立前端 Vue 3 骨架與 protobufjs 靜態 Codegen 驗證.md
  phase 1/
    …
  phase 2/
    …
```

檔名是「六位流水號 + 空格 + 連字號 + 空格 + 功能名稱」。
**流水號跨階段連續**，從 `000001` 開始遞增，**不重用已使用過的號碼**，即使該計畫最後被否決 ——
被否決本身也是要留存的紀錄。

★ **專案其他地方引用報告時只寫編號**（`見 docs/plans/000002 第一節`，程式註解尤其如此）。

## 流程

```
（kickoff：docs/architecture.md 核准）
撰寫報告（含套件教學與程式碼藍圖）→ 告知「報告產好了」→ 等待評估與討論 → 核准 → 使用者親手實作 → AI 驗證、commit 並追加結果
```

狀態：`待核准` → `已核准` → `進行中（步驟 N）` → `已完成（日期）：結果` ／ `作廢（日期）：原因`

---

## Phase 0 · 地基：`.proto` 定義、前後端專案骨架與自動 Codegen

| 編號 | 任務 | 狀態 |
|---|---|---|
| `000001` | **Phase 0 任務拆解** | 已完成（2026-10-16）：拆為 `000002`、`000003` 兩份報告，定案使用 JDK 21 |
| `000002` | 定義共用 `chat.proto` 與後端 Maven Protobuf 編譯＋序列化單元測試 | 已完成（2026-10-16）：4 個測試全綠，Protobuf 34 bytes vs JSON 93 bytes（節省 63.4%） |
| `000003` | 建立前端 Vue 3 骨架與 `protobufjs` 靜態 Codegen 驗證 | 待核准 |

---

## Phase 1 · HTTP REST + Protobuf 二進位收發（預告）

*(待 Phase 0 完成後開立 Phase 1 任務拆解報告)*

---

## Phase 2 · WebSocket + Protobuf 即時通訊聊天室（預告）

*(待 Phase 1 完成後開立 Phase 2 任務拆解報告)*
