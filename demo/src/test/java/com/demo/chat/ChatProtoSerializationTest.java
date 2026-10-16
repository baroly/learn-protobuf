package com.demo.chat;

import com.demo.chat.proto.ChatMessage;
import com.demo.chat.proto.MessageType;
import com.demo.chat.proto.ChatHistoryResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

public class ChatProtoSerializationTest {

    @Test
    @DisplayName("1. 單筆訊息序列化為 byte[] 後反序列化，所有欄位應完全一致，且體積小於對等 JSON")
    void shouldSerializeAndDeserializeChatMessage() throws InvalidProtocolBufferException {
        long now = System.currentTimeMillis();

        ChatMessage message = ChatMessage.newBuilder()
                .setId("msg-001")
                .setSender("Leo")
                .setContent("Hello Tim")
                .setTimestamp(now)
                .setType(MessageType.JOIN).build();

        byte[] binaryBytes = message.toByteArray();

        String equivalentJson = String.format(
                "{\"id\":\"%s\",\"sender\":\"%s\",\"content\":\"%s\",\"timestamp\":%d,\"type\":\"%s\"}",
                message.getId(), message.getSender(), message.getContent(),
                message.getTimestamp(), message.getType().name()
        );
        byte[] jsonBytes = equivalentJson.getBytes(StandardCharsets.UTF_8);

        System.out.println("=== Protobuf vs JSON 體積對比 ===");
        System.out.println("Protobuf bytes 長度 : " + binaryBytes.length + " bytes");
        System.out.println("Protobuf bytes 內容 : " + Arrays.toString(binaryBytes));
        System.out.println("JSON bytes 長度     : " + jsonBytes.length + " bytes");
        System.out.println("JSON 字串內容       : " + equivalentJson);

        // 驗證二進位體積確實比 JSON 更小
        assertThat(binaryBytes.length).isLessThan(jsonBytes.length);

        // 3. 從 byte[] 反序列化還原為新的 ChatMessage 物件
        ChatMessage parsed = ChatMessage.parseFrom(binaryBytes);

        // 4. 驗證每個欄位都精確還原
        assertThat(parsed).isEqualTo(message);
        assertThat(parsed.getId()).isEqualTo("msg-001");
        assertThat(parsed.getSender()).isEqualTo("Leo");
        assertThat(parsed.getContent()).isEqualTo("Hello Tim");
        assertThat(parsed.getTimestamp()).isEqualTo(now);
        assertThat(parsed.getType()).isEqualTo(MessageType.JOIN);
    }

    @Test
    @DisplayName("2. 使用 toBuilder() 修改不可變物件，以及使用 ChatHistoryResponse 包裝多筆訊息")
    void shouldModifyViaToBuilderAndPackRepeatedMessages() throws InvalidProtocolBufferException {
        // 模擬前端只傳來 sender 與 content（未帶 id 與 timestamp）
        ChatMessage fromClient = ChatMessage.newBuilder()
                .setSender("Bot")
                .setContent("第一則訊息").build();

        // proto3 未設定的欄位會自動取得預設值（空字串、0、編號 0 的 CHAT）
        assertThat(fromClient.getId()).isEmpty();
        assertThat(fromClient.getTimestamp()).isZero();
        assertThat(fromClient.getType()).isEqualTo(MessageType.CHAT);

        // 後端使用 toBuilder() 補上伺服器分配的 id 與 timestamp（原物件 fromClient 不會被改變）
        ChatMessage enriched = fromClient.toBuilder()
                .setId("Server-1")
                .setTimestamp(System.currentTimeMillis()).build();

        assertThat(fromClient.getId()).isEmpty();
        assertThat(enriched.getId()).isEqualTo("Server-1");

        // 將多筆訊息放入 ChatHistoryResponse (repeated 欄位)
        ChatHistoryResponse history = ChatHistoryResponse.newBuilder()
                .addMessages(enriched)
                .addMessages(enriched.toBuilder()
                        .setContent("第二則訊息")
                        .setId("Server-2").build()).build();

        // 序列化並反序列化整個列表回應
        byte[] historyByte = history.toByteArray();

        ChatHistoryResponse parsedHistory = ChatHistoryResponse.parseFrom(historyByte);
        assertThat(parsedHistory.getMessagesCount()).isEqualTo(2);
        assertThat(parsedHistory.getMessagesList().get(0).getContent()).isEqualTo("第一則訊息");
        assertThat(parsedHistory.getMessagesList().get(1).getContent()).isEqualTo("第二則訊息");

    }

    @Test
    @DisplayName("3. 解析損毀或非法的二進位資料時，應拋出 InvalidProtocolBufferException")
    void shouldThrowExceptionWhenParsingCorruptedBytes() {
        // 構造一段不合法的 Varint / Wire Type 二進位資料
        byte[] corruptedBytes = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        assertThatThrownBy(() -> ChatMessage.parseFrom(corruptedBytes))
                .isInstanceOf(InvalidProtocolBufferException.class);
    }
}
