<script setup lang="ts">
import {demo} from "./proto/chat";
import {ref} from "vue";

// 從自動生成的命名空間 demo.chat 解構出我們定義的類別與枚舉
const { ChatMessage, ChatHistoryResponse, MessageType } = demo.chat

// 1. 建立單筆訊息（對應 Java 的 ChatMessage.newBuilder()...build()）
const originalMessage = ChatMessage.create({
  id: 'meg-001',
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
      sender: 'tim',
      content: 'Hi Leo! 收到二進位封包',
      timestamp: Date.now(),
      type: MessageType.CHAT
    })
  ]
})

const historyBytes = ChatHistoryResponse.encode(historyPacket).finish()
const decodedHistory = ChatHistoryResponse.decode(historyBytes)

const byteListPreview = ref(Array.from(historyBytes))

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
        <li v-for="msg in decodedHistory.messages" :key="msg.id ?? ''">
          [{{ msg.id }}] <strong>{{ msg.sender }}</strong>: {{ msg.content }}
        </li>
      </ul>
    </section>
  </main>
</template>
