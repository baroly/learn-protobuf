import {demo} from '../proto/chat'

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
        type
    })

    // 2. 序列化為 Uint8Array 二進位陣列
    const outgoingBytes: Uint8Array = ChatMessage.encode(outgoing).finish()

    // 3. 發送 HTTP POST 二進位請求
    const response = await fetch('/api/messages', {
        method: 'POST',
        headers: {
            'Content-Type': PROTOBUF_CONTENT_TYPE,
            Accept: PROTOBUF_CONTENT_TYPE
        },
        body: new Uint8Array(outgoingBytes)
    })

    if(!response.ok) {
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
        type: MessageType[savedMessage.type >> 0]
    })
    const jsonBytes = new TextEncoder().encode(equivalentJson).byteLength

    return {
        message: savedMessage,
        metrics: {
            action: 'Post /api/messages (Response)',
            protobufBytes: responseBytes.length,
            jsonBytes,
            bytePreview: Array.from(responseBytes.slice(0, 24))
        }
    }
}

/**
 * 透過 GET /api/messages 拉取後端記憶體中的歷史訊息列表（ChatHistoryResponse 二進位封包）。
 */
export async function fetchHistoryBinary(): Promise<{messages: demo.chat.ChatMessage.$Properties[], metrics: PacketMetrics}> {

    const response = await fetch('/api/messages', {
        method: 'GET',
        headers: {
            'Accept': 'application/x-protobuf'
        },
    })

    if (!response.ok){
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
            type: MessageType[m.type ?? 0]
        }))
    })
    const jsonBytes = new TextEncoder().encode(equivalentJson).byteLength

    return {
        messages: historyResponse.messages,
        metrics: {
            action: `GET /api/messages (${historyResponse.messages.length}) 筆`,
            protobufBytes: responseBytes.byteLength,
            jsonBytes,
            bytePreview: Array.from(responseBytes.slice(0, 24))
        }
    }
}