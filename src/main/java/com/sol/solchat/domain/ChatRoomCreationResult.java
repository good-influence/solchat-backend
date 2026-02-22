package com.sol.solchat.domain;

/**
 * @param newlyCreated 새로 생성되었는지 여부
 */
public record ChatRoomCreationResult(ChatRoom chatRoom, boolean newlyCreated) {
}
