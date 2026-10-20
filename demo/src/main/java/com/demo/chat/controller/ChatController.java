package com.demo.chat.controller;

import com.demo.chat.proto.ChatMessage;
import com.demo.chat.proto.ChatHistoryResponse;

import com.demo.chat.store.ChatMessageStore;
import com.demo.chat.ws.ChatWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("messages")
public class ChatController {

    private static final String PROTOBUF_MEDIA_TYPE = "application/x-protobuf";

    private final ChatMessageStore chatMessageStore;
    private final ChatWebSocketHandler chatWebSocketHandler;

    public ChatController(ChatMessageStore chatMessageStore, ChatWebSocketHandler chatWebSocketHandler) {
        this.chatMessageStore = chatMessageStore;
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @PostMapping(consumes = PROTOBUF_MEDIA_TYPE, produces = PROTOBUF_MEDIA_TYPE)
    public ChatMessage postMessage(@RequestBody ChatMessage input) {
        ChatMessage saved = chatMessageStore.append(input);
        log.debug("Saved protobuf message id={}, type={}", saved.getId(), saved.getType());
        chatWebSocketHandler.broadCast(saved);
        return saved;
    }

    @GetMapping(produces = PROTOBUF_MEDIA_TYPE)
    public ChatHistoryResponse getMessages() {
        return ChatHistoryResponse.newBuilder()
                .addAllMessages(chatMessageStore.getRecentMessages()).build();
    }

}
