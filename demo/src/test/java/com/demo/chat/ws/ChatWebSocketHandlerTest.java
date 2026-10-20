package com.demo.chat.ws;

import com.demo.chat.proto.ChatMessage;
import com.demo.chat.proto.MessageType;
import com.demo.chat.store.ChatMessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Fail.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class ChatWebSocketHandlerTest {

    private ChatWebSocketHandler handler;
    private ChatMessageStore store;

    @BeforeEach
    void setUp() {
        store = new ChatMessageStore();
        handler = new ChatWebSocketHandler(store);
    }

    private WebSocketSession createMockSession(String id, boolean isOpen) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(isOpen);
        return session;
    }

    @Test
    @DisplayName("1. 收到單一客戶端 BinaryMessage 後，應寫入 Store 並廣播給所有在線客戶端")
    void shouldSavedAndBroadsBinaryMessageToAllSessions() throws Exception {
        WebSocketSession clientA = createMockSession("session-A", true);
        WebSocketSession clientB = createMockSession("session-B", true);

        handler.afterConnectionEstablished(clientA);
        handler.afterConnectionEstablished(clientB);
        assertEquals(2, handler.getActiveSessionCount());

        ChatMessage outgoing = ChatMessage.newBuilder()
                .setSender("Leo")
                .setContent("Hello WebSocket Protobuf!")
                .setType(MessageType.CHAT).build();
        BinaryMessage incoming = new BinaryMessage(outgoing.toByteArray());

        // 由 clientA 發送二進位訊息
        handler.handleMessage(clientA, incoming);

        // 驗證 Store 已存入 1 筆
        assertEquals(1, store.getRecentMessages().size());

        // 驗證 clientA 與 clientB 都收到了廣播的 BinaryMessage，且反序列化後欄位正確
        ArgumentCaptor<BinaryMessage> frameCaptor = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(clientA, times(1)).sendMessage(frameCaptor.capture());
        verify(clientB, times(1)).sendMessage(any(BinaryMessage.class));

        ChatMessage broadcasted = ChatMessage.parseFrom(frameCaptor.getValue().getPayload());
        assertEquals("msg-1", broadcasted.getId());
        assertEquals("Leo", broadcasted.getSender());
        assertEquals("Hello WebSocket Protobuf!", broadcasted.getContent());
        assertTrue(broadcasted.getTimestamp() > 0);
    }

    @Test
    @DisplayName("2. 客戶端斷線後應從連線池移除，後續廣播不再發送給該 Session")
    void shouldRemoveSessionOnClose() throws Exception {
        WebSocketSession clientA = createMockSession("session-A", true);
        handler.afterConnectionEstablished(clientA);
        assertEquals(1, handler.getActiveSessionCount());

        handler.afterConnectionClosed(clientA, CloseStatus.NORMAL);
        assertEquals(0, handler.getActiveSessionCount());
    }

    @Test
    @DisplayName("3. 收到損毀的二進位封包或空白 sender 時，應安全忽略且不影響連線")
    void shouldIgnoreMalformedBinaryOrBlankSender() throws Exception {
        WebSocketSession clientA = createMockSession("session-A", true);
        handler.afterConnectionEstablished(clientA);

        // 傳送損毀的隨機 byte
        handler.handleMessage(clientA, new BinaryMessage(new byte[]{0x08, (byte) 0xFF, (byte) 0xFF}));
        // 傳送空白 sender
        ChatMessage blankSenderMsg = ChatMessage.newBuilder()
                .setSender("")
                .setContent("test").build();
        handler.handleBinaryMessage(clientA, new BinaryMessage(blankSenderMsg.toByteArray()));

        assertEquals(0, store.getRecentMessages().size());
        verify(clientA, never()).sendMessage(any(BinaryMessage.class));
        assertEquals(1, handler.getActiveSessionCount());
    }

    @Test
    @DisplayName("4. 收到純文字 TextMessage (如 JSON) 時，BinaryWebSocketHandler 應以 1003 NOT_ACCEPTABLE 關閉連線")
    void shouldRejectTextMessageWithNotAcceptable() throws Exception {
        WebSocketSession clientA = createMockSession("session-A", true);
        handler.afterConnectionEstablished(clientA);
        handler.handleMessage(clientA, new TextMessage("{\"sender\":\"Leo\"}"));

        // 這種方式會因為 spring 預設返回值不同而抱錯
        // verify(clientA, times(1)).close(captor.capture());

        // 補齊期望回傳參數
        // verify(clientA, times(1)).close(CloseStatus.NOT_ACCEPTABLE.withReason("Text messages not supported"));

        // 使用 ArgumentCaptor 去監測回傳，之後把 code 單獨提出來做比對，這樣之後更換版本有更換回傳訊息也依舊可以正常判斷
        ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(clientA, times(1)).close(captor.capture());
        assertEquals(CloseStatus.NOT_ACCEPTABLE.getCode(), captor.getValue().getCode());

        // assertEquals(0, handler.getActiveSessionCount());
    }

    @Test
    @DisplayName("5. 10 個客戶端同時併發發送 100 則二進位訊息，應全部成功儲存並完成 1,000 次廣播投遞")
    void shouldHandleConcurrentBinaryBroadcastsSafely() throws Exception {
        int clientCount = 10;
        int messagePerClient = 10;
        WebSocketSession[] clients = new WebSocketSession[clientCount];

        for (int i = 0; i < clientCount; i++) {
            clients[i] = createMockSession("session-" + i, true);
            handler.afterConnectionEstablished(clients[i]);
        }

        ExecutorService pool = Executors.newFixedThreadPool(clientCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(clientCount);

        for (int i = 0, j = clientCount; i < j; i++) {
            final int senderIdx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int m = 0, msgCount = messagePerClient; m < msgCount; m++) {
                        ChatMessage msg = ChatMessage.newBuilder()
                                .setSender("User-" + senderIdx)
                                .setContent("Msg-" + m)
                                .setType(MessageType.CHAT).build();

                        handler.handleBinaryMessage(clients[senderIdx], new BinaryMessage(msg.toByteArray()));
                    }
                } catch (Exception e) {
                    fail("併發廣播不應拋出例外: " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.MINUTES));
        pool.shutdown();

        int totalMessages = clientCount * messagePerClient;
        assertEquals(totalMessages, store.getRecentMessages().size());
        for (WebSocketSession client : clients) {
            verify(client, times(totalMessages)).sendMessage(any(BinaryMessage.class));
        }
    }
}
