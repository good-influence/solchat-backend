package com.sol.solchat.constant;

public class ChatConstants {
    // == Redis Keys ==
    // 채팅방 메타데이터 (Last Message 등) 저장용
    public static final String REDIS_CHATROOM_METADATA_PREFIX = "chatroom:metadata:";
    public static final String REDIS_CHATROOM_DIRTY_KEYS = "chatroom:dirty_keys";
    public static final String REDIS_USER_SERVER_KEY = "user:server:";
    public static final String REDIS_ACTIVE_SESSION_KEY_PREFIX = "ws:active_sessions:user:";
    public static final long SESSION_TTL_MINUTES = 30;

    // 사용자 읽음 위치 저장용
    public static final String REDIS_ROOM_READ_POS_PREFIX = "room:read_pos:";
    public static final String REDIS_ROOM_READ_POS_DIRTY_KEYS = "room:read_pos:dirty";

    // == Redis Hash Fields (Map Key) ==
    public static final String KEY_LAST_MESSAGE = "lastMessage";
    public static final String KEY_LAST_SENT_AT = "lastSentAt";
    public static final String KEY_LAST_SENDER_ID = "lastSenderId";
    public static final String KEY_LAST_MESSAGE_ID = "lastMessageId";

    // == Kafka / WebSocket ==
    public static final String WEBSOCKET_DESTINATION_PREFIX = "/topic/chat/room/";

    // 인스턴스화 방지
    private ChatConstants() {}
}
