package com.sol.solchat.scheduler;

import com.sol.solchat.repository.ChatRoomRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sol.solchat.constant.ChatConstants.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatRoomSyncScheduler {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ChatRoomRepository chatRoomRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${chat.server-id}")
    private String serverId;

    /**
     * JPA @Modifying 버전
     * - JPQL 사용하여 SELECT 없이 바로 UPDATE
     */
//    @Scheduled(fixedDelay = 10000)
    public void syncChatRoomMetadata() {
        // 변경된 방 목록 가져오기
        // POP: 꺼내오면서 Redis에서 즉시 삭제됨
        List<Object> dirtyRoomIds = redisTemplate.opsForSet().pop(REDIS_CHATROOM_DIRTY_KEYS, 100);

        if (dirtyRoomIds == null || dirtyRoomIds.isEmpty()) return;

        List<ChatRoomUpdateDto> updateList = new ArrayList<>();

        for (Object roomIdObj : dirtyRoomIds) {
            String roomIdStr = (String) roomIdObj;
            try {
                long roomId = Long.parseLong(roomIdStr);
                String key = REDIS_CHATROOM_METADATA_PREFIX + roomIdStr;

                // Redis에서 최신 정보 가져오기
                Map<Object, Object> metadata = redisTemplate.opsForHash().entries(key);
                if (metadata.isEmpty()) continue;

                String lastMessage = (String) metadata.get(KEY_LAST_MESSAGE);
                String lastSentAtStr = (String) metadata.get(KEY_LAST_SENT_AT);
                String lastMessageIdStr = (String) metadata.get(KEY_LAST_MESSAGE_ID);

                if (lastMessage != null && lastSentAtStr != null && lastMessageIdStr != null) {
                    updateList.add(new ChatRoomUpdateDto(
                            roomId,
                            lastMessage,
                            Long.parseLong(lastSentAtStr),
                            Long.parseLong(lastMessageIdStr)
                    ));
                }
            } catch (Exception e) {
                log.warn("데이터 파싱 에러 (RoomID: {}): {}", roomIdStr, e.getMessage());
            }
        }

        if (updateList.isEmpty()) return;

        try {
            transactionTemplate.executeWithoutResult(status -> {
                for (ChatRoomUpdateDto dto : updateList) {
                    // N번 쿼리
                    chatRoomRepository.updateLastMessage(
                            dto.getRoomId(),
                            dto.getLastMessage(),
                            dto.getLastSentAt(),
                            dto.getLastMessageId()
                    );
                }
            });
            log.info("[JPA Sync] {}개 채팅방 메타데이터 동기화 완료", updateList.size());

        } catch (Exception e) {
            log.error("[JPA Sync] 실패! Redis 키 복구 시도: {}", e.getMessage());
            // [Rollback] DB 실패 시, 꺼내왔던 키들을 다시 Redis에 넣어서 유실 방지
            redisTemplate.opsForSet().add(REDIS_CHATROOM_DIRTY_KEYS, dirtyRoomIds.toArray());
        }
    }

    /**
     * JdbcTemplate Batch Update 버전
     * - 모든 업데이트 하나의 패킷으로 묶어서 전송
     */
    @Scheduled(fixedDelay = 10000)
    public void syncChatRoomMetadata2() {
        // 한 번에 최대 100개씩 가져와서 처리, pop: 꺼내오면서 삭제됨
        List<Object> dirtyRoomIds = redisTemplate.opsForSet().pop(REDIS_CHATROOM_DIRTY_KEYS, 100);
        if (dirtyRoomIds == null || dirtyRoomIds.isEmpty()) return;

        List<ChatRoomUpdateDto> updateList = new ArrayList<>();

        for (Object roomIdObj : dirtyRoomIds) {
            String roomIdStr = (String) roomIdObj;
            try {
                long roomId = Long.parseLong(roomIdStr);
                String key = REDIS_CHATROOM_METADATA_PREFIX + roomIdStr;

                Map<Object, Object> metadata = redisTemplate.opsForHash().entries(key);
                if (metadata.isEmpty()) continue;

                String lastMessage = (String) metadata.get(KEY_LAST_MESSAGE);
                String lastSentAtStr = (String) metadata.get(KEY_LAST_SENT_AT);
                String lastMessageIdStr = (String) metadata.get(KEY_LAST_MESSAGE_ID);

                if (lastMessage != null && lastSentAtStr != null && lastMessageIdStr != null) {
                    updateList.add(new ChatRoomUpdateDto(
                            roomId,
                            lastMessage,
                            Long.parseLong(lastSentAtStr),
                            Long.parseLong(lastMessageIdStr)
                    ));
                }
            } catch (Exception e) {
                log.warn("데이터 파싱 에러 (RoomID: {}): {}", roomIdStr, e.getMessage());
            }
        }

        if (updateList.isEmpty()) return;

        try {
            transactionTemplate.executeWithoutResult(status -> {
                // JDBC Batch Update 실행 (쿼리 한방!)
                batchUpdateChatRooms(updateList);
            });
            log.info("[Sync] {}개 채팅방 메타데이터 동기화 완료 (Server: {})", updateList.size(), serverId);
        } catch (Exception e) {
            log.error("동기화 실패 Redis 키 복구 시도: {}", e.getMessage());

            // [Rollback] DB 실패 시, 꺼내왔던 키들을 다시 Redis에 넣어서 유실 방지
            if (!dirtyRoomIds.isEmpty()) {
                redisTemplate.opsForSet().add(REDIS_CHATROOM_DIRTY_KEYS, dirtyRoomIds.toArray());
            }
        }
    }

    private void batchUpdateChatRooms(List<ChatRoomUpdateDto> updates) {
        String sql = "UPDATE chat_room SET last_message = ?, last_sent_at = ?, last_message_id = ? WHERE id = ?";
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ChatRoomUpdateDto dto = updates.get(i);
                ps.setString(1, dto.getLastMessage());
                ps.setLong(2, dto.getLastSentAt());
                ps.setLong(3, dto.getLastMessageId());
                ps.setLong(4, dto.getRoomId());
            }

            @Override
            public int getBatchSize() {
                return updates.size();
            }
        });
    }

    @Getter
    @AllArgsConstructor
    static class ChatRoomUpdateDto {
        private Long roomId;
        private String lastMessage;
        private Long lastSentAt;
        private Long lastMessageId;
    }
}
