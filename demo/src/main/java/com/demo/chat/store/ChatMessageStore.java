package com.demo.chat.store;

import com.demo.chat.proto.ChatMessage;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class ChatMessageStore {
    /**
     * 記憶體保留的最大訊息筆數上限（硬約束：1,000 則）
     */
    public static final int MAX_MESSAGES = 1000;

    private final ConcurrentLinkedDeque<ChatMessage> messages = new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong(0);

    /**
     * 驗證並寫入一筆新訊息，由伺服器補齊單調遞增 ID 與當前時間戳。
     * 使用 synchronized 確保多執行緒下「序號分配」與「入列順序、容量淘汰」完全一致。
     */
    public synchronized ChatMessage append(ChatMessage incoming) {
        if (incoming == null || incoming.getSender().isBlank()) {
            throw new IllegalArgumentException("Sender could not be blank");
        }

        long seq = sequence.incrementAndGet();

        ChatMessage saved = incoming.toBuilder()
                .setId("msg-" + seq)
                .setTimestamp(System.currentTimeMillis()).build();

        messages.addLast(saved);

        if (messages.size() > MAX_MESSAGES) {
            messages.removeFirst();
        }

        return saved;
    }

    /**
     * 取得目前記憶體中保留的所有歷史訊息（由舊到新排序的不可變快照）。
     */
    public List<ChatMessage> getRecentMessages() {
        return List.copyOf(messages);
    }

    /**
     * 清空所有訊息並重置序號（僅供測試隔離使用）。
     */
    public synchronized void clear() {
        messages.clear();
        sequence.set(0);
    }
}
