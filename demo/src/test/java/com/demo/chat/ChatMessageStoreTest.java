package com.demo.chat;

import com.demo.chat.proto.ChatMessage;

import com.demo.chat.proto.MessageType;
import com.demo.chat.store.ChatMessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ChatMessageStoreTest {

    private ChatMessageStore store;

    @BeforeEach
    void setUp() {
        store = new ChatMessageStore();
    }

    @Test
    @DisplayName("1. 寫入訊息時應由伺服器自動分配 msg-N 序號與時間戳，且空 sender 應拋出 IllegalArgumentException")
    void shouldAssignIdAndTimestampAndRejectBlankSender() {
        ChatMessage incoming = ChatMessage.newBuilder()
                .setSender("Leo")
                .setContent("Hello")
                .setType(MessageType.CHAT).build();

        ChatMessage saved = store.append(incoming);

        assertThat(saved.getId()).isEqualTo("msg-1");
        assertThat(saved.getTimestamp()).isPositive();
        assertThat(saved.getSender()).isEqualTo("Leo");
        assertThat(saved.getContent()).isEqualTo("Hello");

        ChatMessage blankSenderMsg = ChatMessage.newBuilder()
                .setSender("   ")
                .setContent("Invalid").build();

        assertThatThrownBy(() -> store.append(blankSenderMsg))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("2. 寫入超過 1,000 筆上限時，應自動淘汰最舊訊息，且多執行緒併發寫入下序號與順序嚴格一致")
    void shouldEvictOldestMessagesUnderConcurrentWrites() throws InterruptedException {
        int threadCount = 10;
        int messagePerThread = 120;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < messagePerThread; i++) {
                        store.append(ChatMessage.newBuilder()
                                .setSender("User-" + threadId)
                                .setContent("Message " + i)
                                .setType(MessageType.CHAT).build());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 10 個執行緒同時起跑寫入
        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        executor.shutdown();

        assertThat(finished).isTrue();

        List<ChatMessage> recent = store.getRecentMessages();
        // 驗證容量精確保持在 1,000 筆上限
        assertThat(recent).hasSize(ChatMessageStore.MAX_MESSAGES);

        for (int i = 0, j = recent.size(); i < j; i++) {
            long expectedSeq = 201L + i;
            assertThat(recent.get(i).getId()).isEqualTo("msg-" + expectedSeq);
        }
    }
}
