import {demo} from "../proto/chat";
import type {PacketMetrics} from "./chatApi.ts";


const {ChatMessage, MessageType} = demo.chat

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

        const protocol: string = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
        const wsUrl: string = `${protocol}//${window.location.host}/api/ws/chat`

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
            const decodeMsg = ChatMessage.decode(frameBytes)

            // 2. 計算對照組 JSON 的 UTF-8 byte 長度供儀表板展示
            const equivalentJson = JSON.stringify({
                id: decodeMsg.id,
                sender: decodeMsg.sender,
                content: decodeMsg.content,
                timestamp: Number(decodeMsg.timestamp),
                type: MessageType[decodeMsg.type]
            })
            const jsonBytes = new TextEncoder().encode(equivalentJson).byteLength

            this.onMessage(decodeMsg, {
                action: `WS Broadcast (${MessageType[decodeMsg.type]})`,
                protobufBytes: frameBytes.byteLength,
                jsonBytes: jsonBytes,
                bytePreview: Array.from(frameBytes.subarray(0, 24))
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
        if (!this.ws || this.ws.readyState != WebSocket.OPEN) {
            throw new Error('WebSocket 尚未連線，無法發送二進位訊息')
        }

        const outgoing = ChatMessage.create({
            sender,
            content,
            type
        })

        const outgoingBytes: Uint8Array = ChatMessage.encode(outgoing).finish()
        this.ws.send(new Uint8Array(outgoingBytes))
    }

    /**
     * 主動關閉 WebSocket 連線（關閉前可選擇發送 LEAVE 通知）。
     */
    disconnect(leavingSender?: string): void {
        if (!this.ws) return

        if (this.ws.readyState === WebSocket.OPEN && leavingSender && leavingSender.trim()) {
            try {
                this.sendMessage(leavingSender.trim(), `${leavingSender.trim()}  離開了聊天室`, MessageType.LEAVE)
            } catch {
                // ignore error message
            }
        }

        this.ws.close()
        this.ws = null
        this.onStatusChange(false)
    }
}

