package com.sol.solchat.scheduler;

import com.sol.solchat.service.ChatRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.sol.solchat.constant.ChatConstants.REDIS_ROOM_READ_POS_DIRTY_KEYS;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatParticipantSyncScheduler {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ChatRoomService chatRoomService;

    @Value("${chat.server-id}")
    private String serverId;

    @Scheduled(fixedDelay = 10000)
    public void syncReadStatus() {
        List<Object> dirtyRoomIds = redisTemplate.opsForSet().pop(REDIS_ROOM_READ_POS_DIRTY_KEYS, 100);
        if (dirtyRoomIds == null || dirtyRoomIds.isEmpty()) return;

        int successCount = 0;

        for (Object roomIdObj : dirtyRoomIds) {
            String roomIdStr = (String) roomIdObj;
            try {
                long roomId = Long.parseLong(roomIdStr);
                // 트랜잭션 시작 -> 끝나고 커밋 -> 리턴됨
                //chatRoomService.syncReadStatusForRoom(roomId);  // 최적화 1단계
                chatRoomService.syncBulkReadStatusForRoom(roomId); // 최적화 2단계
                successCount ++;
            } catch (Exception e) {
                log.error("읽음 상태 동기화 실패 (RoomId: {}): {}", roomIdStr, e.getMessage());
                // 실패 시, 해당 방 ID만 다시 Redis에 넣어 다음 턴에 재시도
                redisTemplate.opsForSet().add(REDIS_ROOM_READ_POS_DIRTY_KEYS, roomIdObj);
            }
        }

        if (successCount > 0) {
            log.info("[Sync] 읽음 상태 DB 동기화 완료: {}/{} 성공 (Server: {})",
                    successCount, dirtyRoomIds.size(), serverId);
        }
    }
}
