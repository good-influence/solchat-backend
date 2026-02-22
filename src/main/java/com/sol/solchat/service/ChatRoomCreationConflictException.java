package com.sol.solchat.service;

// RuntimeException 상속받아 @Transactional에서 롤백 유도
public class ChatRoomCreationConflictException extends RuntimeException {
    public ChatRoomCreationConflictException(String message) {
        super(message);
    }
}
