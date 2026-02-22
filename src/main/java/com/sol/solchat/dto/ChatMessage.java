package com.sol.solchat.dto;

import lombok.Data;

@Data
public class ChatMessage {
    // 프론트 엔드 전송용: String
    private String id;
    // 메시지 타입 (입장, 퇴장, 채팅)
    private String type;

    private String roomId;

    private String sender;

    private String content;

}
