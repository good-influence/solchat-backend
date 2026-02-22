package com.sol.solchat.service;

import com.sol.solchat.constant.ChatConstants;
import com.sol.solchat.dto.ChatMessage;
import com.sol.solchat.util.IdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ConcurrencyTest {

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    @DisplayName("동시에 여러 메시지 와도 Snowflake ID가 가장 큰(최신) 메시지만 레디스에 남는지 확인")
    void luaScriptConcurrencyTest() throws InterruptedException {

        // 상황 설정
        Long roomId = idGenerator.nextId();
        String metadataKey = ChatConstants.REDIS_CHATROOM_METADATA_PREFIX + roomId;

        // ID 생성 (시간 차를 두고 생성하여 최신 값(더 큰 값) 테스트)
        String smallId = String.valueOf(idGenerator.nextId());  // 과거 메시지

        Thread.sleep(10);

        String largeId = String.valueOf(idGenerator.nextId());  // 최신 메시지

        // 과거 메시지 객체 생성
        ChatMessage oldMessage = new ChatMessage();
        oldMessage.setRoomId(String.valueOf(roomId));
        oldMessage.setId(smallId);
        oldMessage.setContent("나는 옛날 메시지 (덮어씌워지면 안됨)");

        // 최신 메시지 객체 생성
        ChatMessage newMessage = new ChatMessage();
        newMessage.setRoomId(String.valueOf(roomId));
        newMessage.setId(largeId);
        newMessage.setContent("나는 최신 메시지 (내가 남아야 함)");

        // 순서를 뒤집어서 실행(시나리오: 네트워크 지연등 으로 인해 '최신 메시지'가 먼저 처리되고 '옛날 메시지'가 나중에 도착함.
        // 최신 메시지가 먼저 도착해서 저장됨 -> 저장되어야 함!
        chatMessageService.updateChatRoomMetadata(roomId, newMessage);

        // 옛날 메시지가 뒤늦게 도착해서 저장 시도 -> 무시되어야 함!
        chatMessageService.updateChatRoomMetadata(roomId, oldMessage);

        // 레디스 확인
        Map<Object, Object> metadata = redisTemplate.opsForHash().entries(metadataKey);
        String savedContent = (String) metadata.get(ChatConstants.KEY_LAST_MESSAGE);
        String savedId = (String) metadata.get(ChatConstants.KEY_LAST_MESSAGE_ID);

        System.out.println("========================================");
        System.out.println("Room ID: " + roomId);
        System.out.println("Saved Content: " + savedContent);
        System.out.println("Saved ID: " + savedId);
        System.out.println("========================================");

        // 검증 (최신 메시지가 그대로 남아있는지!)
        assertThat(savedContent).isEqualTo("나는 최신 메시지 (내가 남아야 함)");
        assertThat(savedId).isEqualTo(largeId);

        System.out.println("✅ Lua Script 동시성 제어 테스트 성공!");
    }
}
