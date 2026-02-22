package com.sol.solchat.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "chat_message")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @Id
    private Long id;

    // 메시지가 속한 채팅방 ID
    @Column(name = "room_id", nullable = false)
    private Long roomId;

    // 메시지를 보낸 사용자 ID
    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // 메시지 유형 (CHAT(일반 대화), ENTER(사용자 입장), QUIT(사용자 퇴장))
    @Column(nullable = false)
    private String type;

    // 메시지 전송 시각
    @Column(name = "sent_at", nullable = false, updatable = false)
    private Long sentAt;

    @PrePersist
    protected void onCreate() {
        this.sentAt = System.currentTimeMillis();
    }

    public Message(Long roomId, Long senderId, String content, String type) {
        this.roomId = roomId;
        this.senderId = senderId;
        this.content = content;
        this.type = type;
    }
}
