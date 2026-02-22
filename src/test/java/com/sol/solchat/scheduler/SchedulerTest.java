package com.sol.solchat.scheduler;

import com.sol.solchat.constant.ChatConstants;
import com.sol.solchat.domain.ChatRoom;
import com.sol.solchat.repository.ChatRoomRepository;
import com.sol.solchat.util.IdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnableJpaAuditing
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SchedulerTest {

    @Autowired
    private ChatRoomSyncScheduler chatRoomSyncScheduler;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    @DisplayName("Redis 쌓인 변경사항이 스케줄러에 의해 DB로 일괄 반영되는 지 확인")
    void syncSchedulerTest() throws InterruptedException {
        // 테스트용 채팅방 생성 & DB 저장
        Long newId = idGenerator.nextId();
        ChatRoom chatRoom = new ChatRoom(newId, "스케줄러테스트방", "GROUP", UUID.randomUUID().toString());
        chatRoomRepository.save(chatRoom);

        // 레디스에 변경된척 데이터 조작 (Dirty Key 추가)
        String metadataKey = ChatConstants.REDIS_CHATROOM_METADATA_PREFIX + newId;
        redisTemplate.opsForHash().put(metadataKey, ChatConstants.KEY_LAST_MESSAGE, "Redis에서 온 메쉬쥐");
        redisTemplate.opsForHash().put(metadataKey, ChatConstants.KEY_LAST_SENT_AT, String.valueOf(System.currentTimeMillis()));
        redisTemplate.opsForHash().put(metadataKey, ChatConstants.KEY_LAST_MESSAGE_ID, "5000");

        // 작업 대기열(Dirty Keys)에 추가
        redisTemplate.opsForSet().add(ChatConstants.REDIS_CHATROOM_DIRTY_KEYS, String.valueOf(newId));

        // 스케줄러 강제 실행
        chatRoomSyncScheduler.syncChatRoomMetadata2(); // JdbcTemplate 호출 버전

        // 검증 -> Redis Dirty Key는 pop되었으니 사라졌어야 함!
        Boolean isKeyExist = redisTemplate.opsForSet().isMember(ChatConstants.REDIS_CHATROOM_DIRTY_KEYS, String.valueOf(newId));
        assertThat(isKeyExist).isFalse();

        // 검증 -> DB는 업데이트 되었는지
        ChatRoom updatedRoom = chatRoomRepository.findById(newId).orElseThrow();
        assertThat(updatedRoom.getLastMessage()).isEqualTo("Redis에서 온 메쉬쥐");
        assertThat(updatedRoom.getLastMessageId()).isEqualTo(5000L);

        System.out.println("✅ 스케줄러 배치 업데이트 테스트 성공!");
    }
}
