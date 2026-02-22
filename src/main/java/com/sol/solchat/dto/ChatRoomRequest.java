package com.sol.solchat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ChatRoomRequest {
    // 1:1 채팅방 생성을 위한 상대방의 User ID
    @NotNull(message = "상대방 아이디는 필수입니다.")
    private Long targetUserId;
}
