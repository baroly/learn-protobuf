package com.demo.chat.ws;

import com.demo.chat.proto.ChatMessage;
import com.demo.chat.store.ChatMessageStore;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ChatWebSocketHandler extends BinaryWebSocketHandler {

    private final Set<WebSocketSession> sessions;
    private final ChatMessageStore messageStore;

    public ChatWebSocketHandler(ChatMessageStore messageStore) {
        this.sessions = ConcurrentHashMap.newKeySet();
        this.messageStore = messageStore;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        log.info("WebSocket binary connection established: sessionId={}, activeSessions={}",
                session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        log.info("WebSocket binary connection closed: sessionId={}, status={}, activeSessions={}",
                session.getId(), status, sessions.size());
    }

    @Override
    public void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        try{
            // 1. 直接從 NIO ByteBuffer 反序列化為 Protobuf ChatMessage 物件
            ChatMessage incoming = ChatMessage.parseFrom(message.getPayload());

            // 2. 寫入共用記憶體儲存庫（分配單調遞增 msg-N 與伺服器時間戳）
            ChatMessage saved = messageStore.append(incoming);
            log.debug("Received WS binary message: id={}, sender={}, type={}",
                    saved.getId(), saved.getSender(), saved.getType());

            // 3. 序列化為二進位並廣播給所有連線中的客戶端
            broadCast(saved);
        } catch (InvalidProtocolBufferException e) {
            log.warn("Dropped malformed Protobuf binary frame from sessionId={}: {}",
                    session.getId(), e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Dropped invalid ChatMessage from sessionId={}: {}",
                    session.getId(), e.getMessage());
        }

    }

    /**
     * 將已儲存的 ChatMessage 轉為 BinaryMessage 廣播給所有連線中的 WebSocketSession。
     */
    public void broadCast(ChatMessage savedMsg) {
        BinaryMessage outboundFrame = new BinaryMessage(savedMsg.toByteArray());

        for (WebSocketSession client : sessions) {
            if (!client.isOpen()) {
                sessions.remove(client);
                continue;
            }
            try {
                synchronized (client)
                {
                    if (client.isOpen()) {
                        client.sendMessage(outboundFrame);
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to send binary message to sessionId={}, removing session",
                        client.getId(), e);
                sessions.remove(client);
            }
        }
    }

    /**
     * 取得目前在線的 WebSocket 連線數（供測試與監控使用）。
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }
}
