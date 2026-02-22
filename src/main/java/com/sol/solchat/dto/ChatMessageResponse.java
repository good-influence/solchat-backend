package com.sol.solchat.dto;

import com.sol.solchat.domain.Message;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ChatMessageResponse {
    private Long id;
    private Long roomId;
    private String sender;  // 사용자 ID 또는 닉네임
    private String content;
    private String type;
    private Long sendAt;

    public ChatMessageResponse(Message message) {
        this.id = message.getId();
        this.roomId = message.getRoomId();
        this.sender = String.valueOf(message.getSenderId());
        this.content = message.getContent();
        this.type = message.getType();
        this.sendAt = message.getSentAt();
    }
}
