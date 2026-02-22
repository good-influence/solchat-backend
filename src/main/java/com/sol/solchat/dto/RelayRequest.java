package com.sol.solchat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RelayRequest {
    private Long targetUserId;      // 받는 사람 ID
    private ChatMessage message;    // 보낼 메시지 원본
}
