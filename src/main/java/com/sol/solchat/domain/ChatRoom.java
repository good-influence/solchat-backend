package com.sol.solchat.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chat_room")
@Getter
@Setter
@NoArgsConstructor
public class ChatRoom {
    @Id
    private Long id;

    // 1:1 채팅방의 고유 키 (사용자 ID를 정규화 하여 제어, UNIQUE 제약 조건으로 동시성 제어)
    @Column(name = "chat_key", nullable = false, unique = true)
    private String chatKey;

    // 채팅방 이름
    @Column(nullable = false)
    private String name;

    // 채팅방 유형 (DIRECT, GROUP, ...)
    @Column(nullable = false)
    private String type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Long createdAt;

    // 마지막 메시지 내용 (채팅방 목록 조회 시 미리보기용)
    @Column(name = "last_message")
    private String  lastMessage;

    // 마지막 메시지 전송 시간 (정렬용)
    @Column(name = "last_sent_at")
    private Long lastSentAt;

    @Column(name = "last_message_id")
    private Long lastMessageId;

    @PrePersist
    protected void onCreate() {
        this.createdAt = System.currentTimeMillis();
    }

    public ChatRoom(Long id, String name, String type, String chatKey) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.chatKey = chatKey;
    }

    public void updateLastMessage(String message, Long sentAt, Long messageId) {
        this.lastMessage = message;
        this.lastSentAt = sentAt;
        this.lastMessageId = messageId;
    }
}
