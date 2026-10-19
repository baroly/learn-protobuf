<script setup lang="ts">
import { demo } from '../proto/chat'

const { ChatMessage, ChatHistoryResponse, MessageType } = demo.chat

/** Protobuf 二進位專屬的 HTTP Media Type（對應後端 ChatController 的常數） */
const PROTOBUF_CONTENT_TYPE = 'application/x-protobuf'

/**
 * 紀錄單次 HTTP 請求/回應的二進位封包觀測數據，供教學介面展示 Protobuf 與 JSON 的體積差異
 */
export interface PacketMetric {

}


</script>

<template>

</template>

<style scoped>

</style>