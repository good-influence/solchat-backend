package com.sol.solchat.dto;

import com.sol.solchat.domain.ChatRoom;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 클라이언트에게 내려줄 채팅방 정보를 담은 DTO
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatRoomResponse {
    private Long id;
    private String name;
    private String type;
    private String title;   // 방 이름 (1:1 -> 상대방이름, 그룹 -> 방이름)
    private String lastMessage;
    private Long lastSentAt;
    private int unreadCount;  // 안 읽은 메시지 수

    public static ChatRoomResponse from(ChatRoom chatRoom, String title, int unreadCount) {
        return ChatRoomResponse.builder()
                .id(chatRoom.getId())
                .title(title)
                .type(chatRoom.getType())
                .lastMessage(chatRoom.getLastMessage())
                .lastSentAt(chatRoom.getLastSentAt())
                .unreadCount(unreadCount)
                .build();
    }
}
