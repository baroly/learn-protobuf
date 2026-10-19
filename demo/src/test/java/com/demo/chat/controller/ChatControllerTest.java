package com.demo.chat.controller;

import com.demo.chat.proto.ChatHistoryResponse;
import com.demo.chat.proto.ChatMessage;
import com.demo.chat.proto.MessageType;

import com.demo.chat.config.ProtobufConfig;
import com.demo.chat.store.ChatMessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@WebMvcTest
@Import({ProtobufConfig.class, ChatMessageStore.class, GlobalExceptionHandler.class})
public class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatMessageStore chatMessageStore;

    @BeforeEach
    void setUp() {
        chatMessageStore.clear();
    }

    @Test
    @DisplayName("1. POST /api/messages 傳送 Protobuf 二進位應成功儲存並回傳補齊 ID 的二進位，隨後 GET /api/messages 可取得列表")
    void shouldSendAndGetMessagesViaProtobufBinary() throws Exception {
        ChatMessage requestMsg = ChatMessage.newBuilder()
                .setSender("Leo")
                .setContent("Hello Spring Protobuf REST!")
                .setType(MessageType.JOIN).build();

        // 1. 發送 POST /api/messages (Content-Type: application/x-protobuf)
        byte[] postResponseByte = mockMvc.perform(post("/messages")
                        .contentType(ProtobufHttpMessageConverter.PROTOBUF)
                        .accept(ProtobufHttpMessageConverter.PROTOBUF)
                        .content(requestMsg.toByteArray()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(ProtobufHttpMessageConverter.PROTOBUF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        ChatMessage savedMsg = ChatMessage.parseFrom(postResponseByte);
        assertThat(savedMsg.getId()).isEqualTo("msg-1");
        assertThat(savedMsg.getSender()).isEqualTo("Leo");
        assertThat(savedMsg.getContent()).isEqualTo("Hello Spring Protobuf REST!");
        assertThat(savedMsg.getTimestamp()).isPositive();

        // 2. 發送 GET /api/messages 取得 ChatHistoryResponse 二進位
        byte[] getResponseByte = mockMvc.perform(get("/messages")
                        .accept(ProtobufHttpMessageConverter.PROTOBUF))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(ProtobufHttpMessageConverter.PROTOBUF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        ChatHistoryResponse msgHistory = ChatHistoryResponse.parseFrom(getResponseByte);
        assertThat(msgHistory.getMessagesCount()).isEqualTo(1);
        assertThat(msgHistory.getMessages(0)).isEqualTo(savedMsg);

    }

    @Test
    @DisplayName("2. 當客戶端使用 application/json 發送 POST 時，應回傳 415 Unsupported Media Type")
    void shouldRejectJsonContentTypeWith415() throws Exception {
        String jsonPayload = "{\"sender\":\"Leo\",\"content\":\"JSON is not allowed\"}";

        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("3. 當傳送損毀的 Protobuf 二進位或空 sender 時，應回傳 400 Bad Request")
    void shouldReturn400ForCorruptedBinaryOrBlankSender() throws Exception {
        byte[] corruptedBytes = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        mockMvc.perform(post("/messages")
                        .contentType(ProtobufHttpMessageConverter.PROTOBUF)
                        .accept(ProtobufHttpMessageConverter.PROTOBUF)
                        .content(corruptedBytes))
                .andExpect(status().isBadRequest());
    }
}
