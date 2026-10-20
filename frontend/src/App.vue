<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { fetchHistoryBinary, type PacketMetrics, sendMessageBinary } from './api/chatApi'
import { demo } from './proto/chat'

const { MessageType } = demo.chat

// 使用者輸入狀態
const senderName = ref('Leo')
const messageInput = ref('')
const selectedType = ref<demo.chat.MessageType>(MessageType.CHAT)

// 聊天室訊息列表與二進位封包觀測狀態
const messages = ref<demo.chat.ChatMessage.$Properties[]>([])
const latestMetrics = ref<PacketMetrics | null>(null)
const isLoading = ref(false)
const isSending = ref(false)
const errorMessage = ref<string | null>(null)
const messageListRef = ref<HTMLElement | null>(null)

// 計算 Protobuf 相較於 JSON 節省的流量百分比
const savedPercentage = computed(() => {
  if (!latestMetrics.value || latestMetrics.value.jsonBytes === 0) return '0.0'
  const diff = latestMetrics.value.jsonBytes - latestMetrics.value.protobufBytes
  return ((diff / latestMetrics.value.jsonBytes) * 100).toFixed(1)
})

/** 將訊息捲動至最底部 */
async function scrollToBottom() {
  await nextTick()
  if (messageListRef.value) {
    messageListRef.value.scrollTop = messageListRef.value.scrollHeight
  }
}

/** 透過 GET /api/messages 載入後端記憶體中的歷史訊息 */
async function loadHistory() {
  isLoading.value = true
  errorMessage.value = null
  try {
    const result = await fetchHistoryBinary()
    messages.value = result.messages
    latestMetrics.value = result.metrics
    await scrollToBottom()
  } catch (err) {
    errorMessage.value =
      err instanceof Error
        ? `${err.message}（請確認後端 Spring Boot 是否已於 8080 埠啟動）`
        : '載入歷史訊息時發生未知錯誤'
  } finally {
    isLoading.value = false
  }
}

/** 透過 POST /api/messages 發送 Protobuf 二進位訊息 */
async function handleSend() {
  const trimmedSender = senderName.value.trim()
  const trimmedContent = messageInput.value.trim()

  if (!trimmedSender) {
    errorMessage.value = '請先在左側輸入發送者暱稱'
    return
  }
  if (!trimmedContent && selectedType.value === MessageType.CHAT) {
    return
  }

  // 若發送 JOIN 或 LEAVE 事件且未填內容，自動補上預設系統提示詞
  const finalContent =
    trimmedContent ||
    (selectedType.value === MessageType.JOIN
      ? `${trimmedSender} 加入了聊天室`
      : `${trimmedSender} 離開了聊天室`)

  isSending.value = true
  errorMessage.value = null
  try {
    const result = await sendMessageBinary(trimmedSender, finalContent, selectedType.value)
    messages.value.push(result.message)
    latestMetrics.value = result.metrics
    messageInput.value = ''
    selectedType.value = MessageType.CHAT
    await scrollToBottom()
  } catch (err) {
    errorMessage.value =
      err instanceof Error
        ? `${err.message}（請確認後端 Spring Boot 是否已於 8080 埠啟動）`
        : '發送訊息時發生未知錯誤'
  } finally {
    isSending.value = false
  }
}

/** 將 Protobuf int64 (number | Long) 時間戳格式化為易讀的本地時間 */
function formatTimestamp(ts?: number | Long | null): string {
  if (!ts) return ''
  const date = new Date(Number(ts))
  return date.toLocaleTimeString('zh-TW', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

/** 取得 MessageType 對應的標籤文字 */
function getTypeLabel(type?: demo.chat.MessageType | null): string {
  if (type === MessageType.JOIN) return 'JOIN'
  if (type === MessageType.LEAVE) return 'LEAVE'
  return 'CHAT'
}

onMounted(() => {
  loadHistory()
})
</script>

<template>
  <div class="page-wrapper">
    <!-- 頂部導覽列 -->
    <header class="top-header">
      <div class="header-inner">
        <div class="brand-title">
          <span class="protocol-badge">Phase 1 · HTTP Binary</span>
          <h1>Protobuf 二進位即時聊天室</h1>
        </div>
        <div class="header-meta">
          <span>Content-Type: <code>application/x-protobuf</code></span>
        </div>
      </div>
    </header>

    <!-- 雙欄主容器（對齊 crypt-fiction IndexView 淺藍卡片設計） -->
    <main class="container">
      <!-- 左側欄：使用者設定與 Protobuf 封包即時觀測儀表板 -->
      <aside class="sidebar">
        <section class="sidebar-block">
          <h2 class="section-title">使用者與發送設定</h2>
          <div class="form-group">
            <label for="sender-input">發送者暱稱 (sender)</label>
            <input
              id="sender-input"
              v-model="senderName"
              type="text"
              class="text-input"
              placeholder="請輸入暱稱，例如 Leo"
              maxlength="24"
            />
          </div>

          <div class="form-group">
            <label>訊息類型 (MessageType)</label>
            <div class="type-selector">
              <button
                type="button"
                class="type-btn"
                :class="{ active: selectedType === MessageType.CHAT }"
                @click="selectedType = MessageType.CHAT"
              >
                CHAT (0)
              </button>
              <button
                type="button"
                class="type-btn"
                :class="{ active: selectedType === MessageType.JOIN }"
                @click="selectedType = MessageType.JOIN"
              >
                JOIN (1)
              </button>
              <button
                type="button"
                class="type-btn"
                :class="{ active: selectedType === MessageType.LEAVE }"
                @click="selectedType = MessageType.LEAVE"
              >
                LEAVE (2)
              </button>
            </div>
          </div>
        </section>

        <section class="sidebar-block">
          <h2 class="section-title">Protobuf 封包觀測儀表板</h2>
          <div v-if="latestMetrics" class="metrics-card">
            <div class="metric-action">{{ latestMetrics.action }}</div>

            <div class="metric-comparison">
              <div class="metric-box proto">
                <span class="metric-label">Protobuf 二進位</span>
                <strong class="metric-value">{{ latestMetrics.protobufBytes }} <small>B</small></strong>
              </div>
              <div class="metric-vs">vs</div>
              <div class="metric-box json">
                <span class="metric-label">對等 JSON (UTF-8)</span>
                <strong class="metric-value">{{ latestMetrics.jsonBytes }} <small>B</small></strong>
              </div>
            </div>

            <div class="saving-banner">
              流量節省：<strong>{{ savedPercentage }}%</strong>
            </div>

            <div class="byte-preview-block">
              <div class="byte-preview-title">
                Uint8Array 前 {{ latestMetrics.bytePreview.length }} Bytes (十進位)：
              </div>
              <div class="byte-grid">
                <span
                  v-for="(b, idx) in latestMetrics.bytePreview"
                  :key="idx"
                  class="byte-cell"
                >
                  {{ b }}
                </span>
                <span v-if="latestMetrics.bytePreview.length === 0" class="empty-bytes">
                  (0 bytes — 空封包)
                </span>
              </div>
            </div>
          </div>
          <div v-else class="metrics-empty">
            尚無封包紀錄，請發送訊息或點擊右側重新載入。
          </div>
        </section>
      </aside>

      <!-- 右側主內容區：訊息列表與發送列 -->
      <section class="main-content">
        <div class="chat-header">
          <div class="chat-title-group">
            <h2 class="section-title">聊天室訊息紀錄</h2>
            <span class="msg-count-badge">共 {{ messages.length }} 則訊息</span>
          </div>
          <button
            type="button"
            class="secondary-btn"
            :disabled="isLoading"
            @click="loadHistory"
          >
            {{ isLoading ? '載入中...' : '↻ 重新載入 (GET /api/messages)' }}
          </button>
        </div>

        <!-- 錯誤提示 Banner -->
        <div v-if="errorMessage" class="error-banner">
          <span>⚠️ {{ errorMessage }}</span>
          <button type="button" class="close-err-btn" @click="errorMessage = null">✕</button>
        </div>

        <!-- 訊息滾動列表 -->
        <div ref="messageListRef" class="message-list">
          <div v-if="messages.length === 0 && !isLoading" class="empty-chat">
            <p>目前後端記憶體中尚無聊天訊息</p>
            <small>在下方輸入框打字並點擊「發送」，即可送出第一筆 Protobuf 二進位封包！</small>
          </div>

          <template v-for="(msg, index) in messages" :key="msg.id || index">
            <!-- 系統事件訊息（JOIN / LEAVE） -->
            <div
              v-if="msg.type === MessageType.JOIN || msg.type === MessageType.LEAVE"
              class="system-message-row"
            >
              <span
                class="system-badge"
                :class="msg.type === MessageType.JOIN ? 'join' : 'leave'"
              >
                {{ getTypeLabel(msg.type) }}
              </span>
              <span class="system-text">
                <strong>{{ msg.sender }}</strong>：{{ msg.content }}
              </span>
              <span class="system-meta">#{{ msg.id }} · {{ formatTimestamp(msg.timestamp) }}</span>
            </div>

            <!-- 一般聊天訊息氣泡（CHAT） -->
            <div
              v-else
              class="chat-row"
              :class="{ 'is-mine': msg.sender === senderName.trim() }"
            >
              <div class="chat-bubble">
                <div class="bubble-header">
                  <strong class="sender-name">{{ msg.sender }}</strong>
                  <span class="msg-id">#{{ msg.id }}</span>
                </div>
                <div class="bubble-content">{{ msg.content }}</div>
                <div class="bubble-footer">
                  <span class="type-tag">{{ getTypeLabel(msg.type) }}</span>
                  <span class="msg-time">{{ formatTimestamp(msg.timestamp) }}</span>
                </div>
              </div>
            </div>
          </template>
        </div>

        <!-- 底部訊息輸入與發送列 -->
        <form class="chat-input-bar" @submit.prevent="handleSend">
          <input
            v-model="messageInput"
            type="text"
            class="chat-input"
            :placeholder="
              selectedType === MessageType.CHAT
                ? `以 ${senderName || '匿名'} 的身分輸入訊息... (Enter 發送)`
                : `發送 ${getTypeLabel(selectedType)} 通知（可留空使用預設文字）...`
            "
            :disabled="isSending"
          />
          <button
            type="submit"
            class="primary-btn"
            :disabled="isSending || (selectedType === MessageType.CHAT && !messageInput.trim())"
          >
            {{ isSending ? '發送中...' : '發送 Protobuf (POST)' }}
          </button>
        </form>
      </section>
    </main>
  </div>
</template>

<style scoped>
.page-wrapper {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.top-header {
  background-color: var(--card-bg);
  border-bottom: 1px solid var(--border-color);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.03);
  padding: 12px 24px;
}

.header-inner {
  max-width: 1180px;
  margin: 0 auto;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.brand-title {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-title h1 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 700;
  color: var(--text-main);
}

.protocol-badge {
  background-color: var(--primary-light);
  color: var(--primary-color);
  border: 1px solid rgba(69, 150, 255, 0.35);
  font-size: 0.78rem;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 20px;
}

.header-meta {
  font-size: 0.88rem;
  color: var(--text-secondary);
}

/* 雙欄卡片主容器（對齊 crypt-fiction IndexView.vue） */
.container {
  max-width: 1180px;
  width: 95%;
  margin: 24px auto;
  background-color: var(--card-bg);
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.05);
  display: flex;
  flex: 1;
  min-height: 640px;
  max-height: calc(100vh - 110px);
  overflow: hidden;
}

/* 左側邊欄 */
.sidebar {
  width: 320px;
  background-color: var(--surface-bg);
  border-right: 1px solid var(--border-color);
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 24px;
  overflow-y: auto;
}

.sidebar-block {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.section-title {
  font-size: 1.02rem;
  font-weight: 700;
  margin: 0;
  padding-left: 10px;
  border-left: 4px solid var(--primary-color);
  color: var(--text-main);
  line-height: 1.3;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-group label {
  font-size: 0.84rem;
  font-weight: 600;
  color: var(--text-secondary);
}

.text-input {
  padding: 9px 12px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.92rem;
  background-color: var(--card-bg);
  color: var(--text-main);
  transition: border-color 0.2s, box-shadow 0.2s;
}

.text-input:focus {
  outline: none;
  border-color: var(--primary-color);
  box-shadow: 0 0 0 3px rgba(69, 150, 255, 0.15);
}

.type-selector {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}

.type-btn {
  padding: 7px 6px;
  font-size: 0.78rem;
  font-weight: 600;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background-color: var(--card-bg);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s;
}

.type-btn:hover {
  border-color: var(--primary-color);
  color: var(--primary-color);
}

.type-btn.active {
  background-color: var(--primary-color);
  border-color: var(--primary-color);
  color: #ffffff;
}

/* 封包觀測卡片 */
.metrics-card {
  background-color: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.metric-action {
  font-family: var(--mono);
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--primary-hover);
  background-color: var(--primary-light);
  padding: 5px 8px;
  border-radius: 4px;
  word-break: break-all;
}

.metric-comparison {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.metric-box {
  flex: 1;
  padding: 8px 10px;
  border-radius: 6px;
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.metric-box.proto {
  background-color: var(--primary-light);
  border: 1px solid rgba(69, 150, 255, 0.3);
}

.metric-box.json {
  background-color: var(--surface-bg);
  border: 1px solid var(--border-color);
}

.metric-label {
  font-size: 0.74rem;
  color: var(--text-secondary);
}

.metric-value {
  font-size: 1.15rem;
  font-family: var(--mono);
  color: var(--text-main);
}

.metric-box.proto .metric-value {
  color: var(--primary-hover);
}

.metric-vs {
  font-size: 0.78rem;
  color: var(--text-light);
  font-weight: 600;
}

.saving-banner {
  background-color: var(--success-bg);
  color: var(--success-color);
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 0.85rem;
  text-align: center;
}

.byte-preview-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.byte-preview-title {
  font-size: 0.76rem;
  color: var(--text-secondary);
}

.byte-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  background-color: var(--surface-bg);
  padding: 8px;
  border-radius: 6px;
  border: 1px solid var(--border-color);
}

.byte-cell {
  font-family: var(--mono);
  font-size: 0.74rem;
  background-color: var(--card-bg);
  border: 1px solid var(--border-color);
  padding: 1px 5px;
  border-radius: 3px;
  color: var(--text-main);
}

.empty-bytes {
  font-size: 0.78rem;
  color: var(--text-light);
}

.metrics-empty {
  font-size: 0.85rem;
  color: var(--text-light);
  background-color: var(--card-bg);
  border: 1px dashed var(--border-color);
  border-radius: 8px;
  padding: 16px;
  text-align: center;
}

/* 右側主內容區 */
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  background-color: var(--card-bg);
  min-width: 0;
}

.chat-header {
  padding: 16px 24px;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.chat-title-group {
  display: flex;
  align-items: center;
  gap: 12px;
}

.msg-count-badge {
  font-size: 0.8rem;
  color: var(--text-secondary);
  background-color: var(--surface-bg);
  border: 1px solid var(--border-color);
  padding: 2px 10px;
  border-radius: 12px;
}

.secondary-btn {
  padding: 7px 14px;
  font-size: 0.84rem;
  font-weight: 600;
  color: var(--primary-color);
  background-color: var(--primary-light);
  border: 1px solid rgba(69, 150, 255, 0.3);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.secondary-btn:hover:not(:disabled) {
  background-color: var(--primary-color);
  color: #ffffff;
}

.secondary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.error-banner {
  margin: 12px 24px 0;
  padding: 10px 14px;
  background-color: var(--danger-bg);
  color: var(--danger-color);
  border: 1px solid rgba(229, 57, 53, 0.3);
  border-radius: 6px;
  font-size: 0.88rem;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.close-err-btn {
  background: none;
  border: none;
  color: var(--danger-color);
  cursor: pointer;
  font-size: 1rem;
}

/* 訊息列表區 */
.message-list {
  flex: 1;
  padding: 20px 24px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background-color: #fafbfc;
}

.empty-chat {
  margin: auto;
  text-align: center;
  color: var(--text-light);
  padding: 40px 20px;
}

.empty-chat p {
  margin: 0 0 6px;
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-secondary);
}

/* 系統通知列 (JOIN / LEAVE) */
.system-message-row {
  align-self: center;
  background-color: var(--surface-bg);
  border: 1px solid var(--border-color);
  border-radius: 20px;
  padding: 5px 14px;
  font-size: 0.82rem;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  gap: 8px;
}

.system-badge {
  font-size: 0.7rem;
  font-weight: 700;
  padding: 1px 7px;
  border-radius: 10px;
  text-transform: uppercase;
}

.system-badge.join {
  background-color: var(--success-bg);
  color: var(--success-color);
}

.system-badge.leave {
  background-color: #fff3e0;
  color: #ef6c00;
}

.system-meta {
  font-size: 0.74rem;
  color: var(--text-light);
  font-family: var(--mono);
}

/* 聊天氣泡 */
.chat-row {
  display: flex;
  justify-content: flex-start;
}

.chat-row.is-mine {
  justify-content: flex-end;
}

.chat-bubble {
  max-width: 72%;
  min-width: 180px;
  background-color: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  border-top-left-radius: 4px;
  padding: 10px 14px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.03);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.chat-row.is-mine .chat-bubble {
  background-color: var(--primary-light);
  border-color: rgba(69, 150, 255, 0.35);
  border-top-left-radius: 12px;
  border-top-right-radius: 4px;
}

.bubble-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.sender-name {
  font-size: 0.85rem;
  color: var(--primary-hover);
}

.msg-id {
  font-family: var(--mono);
  font-size: 0.74rem;
  color: var(--text-light);
}

.bubble-content {
  font-size: 0.95rem;
  color: var(--text-main);
  word-break: break-word;
}

.bubble-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
}

.type-tag {
  font-family: var(--mono);
  font-size: 0.68rem;
  padding: 1px 6px;
  border-radius: 4px;
  background-color: rgba(69, 150, 255, 0.12);
  color: var(--primary-hover);
}

.msg-time {
  font-size: 0.74rem;
  color: var(--text-light);
}

/* 底部輸入列 */
.chat-input-bar {
  padding: 16px 24px;
  border-top: 1px solid var(--border-color);
  background-color: var(--card-bg);
  display: flex;
  gap: 12px;
}

.chat-input {
  flex: 1;
  padding: 11px 14px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.95rem;
  color: var(--text-main);
  transition: border-color 0.2s, box-shadow 0.2s;
}

.chat-input:focus {
  outline: none;
  border-color: var(--primary-color);
  box-shadow: 0 0 0 3px rgba(69, 150, 255, 0.15);
}

.primary-btn {
  padding: 0 22px;
  background-color: var(--primary-color);
  color: #ffffff;
  border: none;
  border-radius: 8px;
  font-size: 0.92rem;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.2s;
  white-space: nowrap;
}

.primary-btn:hover:not(:disabled) {
  background-color: var(--primary-hover);
}

.primary-btn:disabled {
  background-color: #a0c8ff;
  cursor: not-allowed;
}

@media (max-width: 860px) {
  .container {
    flex-direction: column;
    max-height: none;
  }
  .sidebar {
    width: 100%;
    border-right: none;
    border-bottom: 1px solid var(--border-color);
  }
}
</style>
