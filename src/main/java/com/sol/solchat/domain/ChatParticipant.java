package com.sol.solchat.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_participant",
    uniqueConstraints = {
        // 한 채팅방에 한 사용자가 중복 참여하는 것 방지
        @UniqueConstraint(columnNames = {"chat_room_id", "user_id"})
    }
)
@Getter
@Setter
@NoArgsConstructor
public class ChatParticipant {
    @Id
    private Long id;

    // 속한 채팅방 ID
    @Column(name = "chat_room_id", nullable = false)
    private Long chatRoomId;

    // 참여 사용자 ID
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 마지막으로 읽은 메시지 ID (초기값 0)
    @Column(name = "last_read_message_id", nullable = false)
    private Long lastReadMessageId = 0L;

    public ChatParticipant(Long id, Long chatRoomId, Long userId) {
        this.id = id;
        this.chatRoomId = chatRoomId;
        this.userId = userId;
        this.lastReadMessageId = 0L;
    }

    // 읽은 위치 업데이트
    public void updateLastReadMessageId(Long messageId) {
        // 더 최신 메시지를 읽었을 때만 업데이트
        if (this.lastReadMessageId < messageId) {
            this.lastReadMessageId = messageId;
        }
    }
}
